package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.school.ontology.common.ApiException;
import com.school.ontology.common.Resp;
import com.school.ontology.repository.OntologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对象类型在线编辑的编排层（C 档，机制对齐 {@link RelationTypeDefinitionEditor}）。
 *
 * <p>六步固定顺序：版本闸门 → 草稿文档副本改 → 字段级校验 → 集成校验 →
 * YAML 序列化回解自检 → 原子写 overlay + 登记 PENDING。</p>
 *
 * <p><b>v1 硬边界</b>：只开放 displayName / description / titleTemplate /
 * listFields / searchableFields / filterableFields 六个展示层字段。
 * id / identity / sourceMappingRef / attributes / derivedProperties / dynamicResolvers
 * 任一变化一律 400，并逐条说明「为什么」与安全路径；新增 / 删除对象类型同样不支持。</p>
 *
 * <p>保存<b>不等于</b>生效：本类不碰运行期 {@code ModelRegistry}，
 * 真正换定义靠 {@link DefinitionApplyService} 触发的受控重启。</p>
 */
@Component
public class ObjectTypeDefinitionEditor {

    /** 与原业务 change_records.operator 一致的默认操作人。 */
    public static final String DEFAULT_OPERATOR = "演示管理员";
    private static final String SOURCE_OVERLAY = "OVERLAY";
    private static final String SOURCE_BASELINE = "BASELINE";
    /** 六个允许编辑的字段；其余字段的比较一律按「不可变」处理。 */
    private static final Set<String> EDITABLE_KEYS = Set.of(
            "displayName", "description", "titleTemplate",
            "listFields", "searchableFields", "filterableFields");
    private static final Map<String, String> IMMUTABLE_LABELS = Map.of(
            "id", "id",
            "identity", "identity（来源表 / 主键 / globalIdRule）",
            "sourceMappingRef", "sourceMappingRef",
            "attributes", "attributes（含属性 displayName 与增删改）",
            "derivedProperties", "derivedProperties",
            "dynamicResolvers", "dynamicResolvers");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Logger log = LoggerFactory.getLogger(ObjectTypeDefinitionEditor.class);

    private final ModelDefinitionLoader loader;
    private final DefinitionFileStore fileStore;
    private final DefinitionFileStore relationStore;
    private final OntologyRepository repository;

    /** 单测构造：只有对象 overlay；hash 里的关系文本取打包基线。 */
    public ObjectTypeDefinitionEditor(ModelDefinitionLoader loader, DefinitionFileStore fileStore,
                                      OntologyRepository repository) {
        this(loader, fileStore, null, repository);
    }

    @Autowired
    public ObjectTypeDefinitionEditor(
            ModelDefinitionLoader loader,
            @Qualifier("objectDefinitionFileStore") DefinitionFileStore fileStore,
            @Qualifier("relationDefinitionFileStore") DefinitionFileStore relationStore,
            OntologyRepository repository) {
        this.loader = loader;
        this.fileStore = fileStore;
        this.relationStore = relationStore;
        this.repository = repository;
    }

    // ── 保存草稿 ────────────────────────────────────────────────────────────

    /**
     * 修改一条既有对象类型（只改展示层六个字段）。
     *
     * @param objectTypeId 对象类型 id，必须在当前生效 / 草稿定义中存在
     * @param request      含完整 objectType 的编辑请求
     * @return 草稿保存结果（applied 恒为 false，带 impact 影响评估）
     */
    public Map<String, Object> update(String objectTypeId, ObjectEditRequest request) {
        String sourceId = sourceId();
        guardBaseVersion(request, sourceId);
        guardTaskConflict();
        ModelDocument.ObjectTypeDocument document = draftDocument();
        ObjectTypeDef current = find(document, objectTypeId);
        if (objectTypeId == null || current == null) {
            throw ApiException.notFound("对象类型不存在：" + objectTypeId
                    + "；v1 不支持新增 / 删除对象类型，请先确认 id 或联系维护人员在 object-types.yaml 中补充来源映射");
        }
        ObjectTypeDef candidate = request.objectType;
        List<String> errors = validateFields(objectTypeId, current, candidate);
        if (errors.isEmpty()) {
            replace(document, objectTypeId, copyForSave(candidate));
            errors = loader.validate(document, loader.relationDocument(), loader.sourceDocument());
        }
        failOnErrors(errors, objectTypeId);
        Map<String, Object> impact = impact(objectTypeId, current, candidate);
        return save(document, sourceId, request,
                blankToDefault(request.note, "编辑对象类型 " + objectTypeId), impact);
    }

    // ── 恢复基线 ────────────────────────────────────────────────────────────

    /** 只删除对象 overlay；关系 overlay 与其它在线改动完全不受影响。 */
    public Map<String, Object> reset(ObjectEditRequest request) {
        String sourceId = sourceId();
        guardBaseVersion(request, sourceId);
        if (!request.confirm) {
            throw ApiException.badRequest("恢复对象类型基线会删除全部对象在线改动，需要二次确认（confirm=true）");
        }
        if (!fileStore.overlayExists()) {
            return Resp.map("reset", true, "noChange", true, "applied", false,
                    "definitionSource", SOURCE_BASELINE, "pendingMappingVersion", null,
                    "message", "当前没有对象类型在线改动，对象定义已在打包基线上");
        }
        String operator = blankToDefault(request.operator, DEFAULT_OPERATOR);
        String note = blankToDefault(request.note, "恢复对象类型打包基线");
        fileStore.deleteOverlay();
        // 与关系 reset 对齐：草稿随 overlay 消失，登记「回到基线」的待生效版本并清掉幽灵 PENDING。
        String target = DefinitionDraftHash.version(sourceId, loader, fileStore, relationStore);
        String hash = "sha256:" + DefinitionDraftHash.hash(loader, fileStore, relationStore);
        try {
            repository.markPendingMappingVersion(sourceId, target, hash, operator, note, SOURCE_BASELINE);
            repository.supersedeOtherPending(sourceId, target);
        } catch (Exception e) {
            log.warn("对象定义恢复基线后登记版本失败（文件已回退，激活时会重新解析署名）：{}", e.getMessage());
        }
        return Resp.map("reset", true, "applied", false,
                "definitionSource", SOURCE_BASELINE,
                "pendingMappingVersion", target,
                "operator", operator, "note", note,
                "message", "已删除对象类型在线改动（关系类型在线改动保留），点击『应用到运行时』后生效");
    }

    // ── 草稿状态 ────────────────────────────────────────────────────────────

    /**
     * {@code GET /model/object-types/draft} 的负载。无草稿时 exists=false，
     * objectTypes 仍返回当前生效定义的完整副本（供编辑器直接构造提交体）。
     */
    public Map<String, Object> draft() {
        String sourceId = sourceId();
        boolean exists = fileStore.overlayExists();
        String draftVersion = DefinitionDraftHash.hasDraft(fileStore, relationStore)
                ? DefinitionDraftHash.version(sourceId, loader, fileStore, relationStore) : null;
        String activeVersion = firstText(safeActiveRow(sourceId).get("mapping_version"),
                loader.mappingVersion().mappingVersion);
        Map<String, Object> pendingRow = safePendingRow(sourceId);
        String pendingVersion = firstText(pendingRow.get("mapping_version"), null);
        boolean pendingApply = draftVersion != null && !draftVersion.equals(activeVersion)
                || !exists && pendingVersion != null && !pendingVersion.equals(activeVersion);
        Map<String, Object> target = Resp.map(
                "exists", exists,
                "draftMappingVersion", draftVersion,
                "draftDefinitionSource", exists ? SOURCE_OVERLAY : null,
                "savedAt", pendingRow.isEmpty() ? null : Resp.ts(OntologyRepository.dateTime(pendingRow.get("activated_at"))),
                "savedBy", pendingRow.isEmpty() ? null : OntologyRepository.str(pendingRow.get("operator")),
                "savedNote", pendingRow.isEmpty() ? null : OntologyRepository.str(pendingRow.get("note")),
                "pendingApply", pendingApply,
                "pendingTargetVersion", pendingApply ? (draftVersion == null ? pendingVersion : draftVersion) : null,
                "pendingTargetSource", pendingApply ? (exists ? SOURCE_OVERLAY : SOURCE_BASELINE) : null,
                "objectTypes", draftViews(),
                "objectTypesFromDraft", exists);
        return target;
    }

    /** 当前草稿 / 生效对象的可编辑视图清单（供版本面板与前端表单复用）。 */
    public List<Map<String, Object>> draftViews() {
        return objectViews(draftDocument());
    }

    /** 对象 overlay 是否存在（供版本面板区分「对象草稿」与「关系草稿」）。 */
    public boolean objectOverlayExists() {
        return fileStore.overlayExists();
    }

    /** 计算对象 overlay 的完整 YAML 草稿版本（供管理页对照）。 */
    public String draftVersion(String sourceId) {
        if (!fileStore.overlayExists()) {
            return null;
        }
        return DefinitionDraftHash.version(sourceId, loader, fileStore, relationStore);
    }

    // ── 内部：校验 ──────────────────────────────────────────────────────────

    /**
     * 字段级校验：可变字段合法性 + 不可变字段逐项硬拦截。
     *
     * <p>不可变比较用 Jackson 把对象转成 Map、剔除六个允许字段后逐项比对，
     * 因而属性 displayName、required、from 等嵌套变化也不会漏掉。</p>
     */
    private List<String> validateFields(String objectTypeId, ObjectTypeDef current, ObjectTypeDef candidate) {
        List<String> errors = new ArrayList<>();
        if (candidate == null) {
            errors.add("objectType 不能为空：请提交完整的对象类型定义");
            return errors;
        }
        String label = "对象类型[" + objectTypeId + "]";
        if (isBlank(candidate.id) || !objectTypeId.equals(candidate.id) || !objectTypeId.equals(current.id)) {
            errors.add("id 不可编辑（提交值=" + String.valueOf(candidate.id) + "）：对象 id 是 globalId 身份链的一环，"
                    + "改名会让历史关系与变化记录全部失配；请保留原 id，只改展示层字段");
        }
        if (isBlank(candidate.displayName)) {
            errors.add(label + " 的 displayName 不能为空：探索器按钮与导航都依赖它显示");
        }
        if (isBlank(candidate.titleTemplate)) {
            errors.add(label + " 的 titleTemplate 不能为空：对象标题由它渲染");
        }
        Map<String, Object> before = fingerprint(current);
        Map<String, Object> after = fingerprint(candidate);
        for (Map.Entry<String, String> entry : IMMUTABLE_LABELS.entrySet()) {
            if (!Objects.equals(before.get(entry.getKey()), after.get(entry.getKey()))) {
                errors.add(immutableReason(entry.getKey(), entry.getValue()));
            }
        }
        Set<String> known = current.knownFieldNames();
        checkFields(errors, label, "listFields", candidate.listFields, known, Set.of());
        checkFields(errors, label, "searchableFields", candidate.searchableFields, known, Set.of());
        // 存量豁免：基线 filterableFields 含来源列（schoolId/classId/teacherId 等），
        // 它们不是展示属性但由查询层白名单消费；只允许「本来就存在」的，新增非法字段一律拒绝。
        checkFields(errors, label, "filterableFields", candidate.filterableFields, known,
                current.filterableFields == null ? Set.of()
                        : new LinkedHashSet<>(current.filterableFields));
        Matcher matcher = PLACEHOLDER.matcher(candidate.titleTemplate == null ? "" : candidate.titleTemplate);
        Set<String> illegal = new LinkedHashSet<>();
        while (matcher.find()) {
            String field = matcher.group(1);
            if (!known.contains(field)) {
                illegal.add(field);
            }
        }
        if (!illegal.isEmpty()) {
            errors.add(label + " 的 titleTemplate 占位符不是既有属性名：" + String.join("、", illegal)
                    + "；可用字段：" + String.join("、", known));
        }
        return errors;
    }

    /** 不可变字段的拒绝原因必须同时给出「为什么」与「安全路径」。 */
    private static String immutableReason(String key, String label) {
        return switch (key) {
            case "id" -> "id 不可编辑：修改对象 id 会断开 globalId 身份链，历史关系 / 变化记录全部失配；"
                    + "如需换名请新增带来源映射的对象类型并重新导入";
            case "identity" -> "identity（sourceId/sourceTable/sourcePk/globalIdRule）不可编辑："
                    + "稳定身份由来源映射决定，改了会重算全部 globalId 并丢失历史关联；"
                    + "如需调整请在 school-source.yaml 改映射后做全量导入";
            case "sourceMappingRef" -> "sourceMappingRef 不可编辑：它指向 school-source.yaml 的来源映射，"
                    + "改了会让对象与投影数据脱钩；请先在来源映射中调整再重新导入";
            case "attributes" -> "attributes 不可增删改（含属性 displayName）：属性结构由来源映射决定且已有投影数据，"
                    + "改了会出现空列与历史值失配；请在 school-source.yaml 增加字段映射后全量导入";
            case "derivedProperties" -> "derivedProperties 不可编辑：派生属性的 provider/resolver 与投影数据需要重建验证；"
                    + "请改 school-source.yaml 的绑定后做全量重建";
            case "dynamicResolvers" -> "dynamicResolvers 不可编辑：动态结果依赖已注册的 Resolver 与投影数据；"
                    + "请改 school-source.yaml 的注册后做全量重建";
            default -> label + " 不可编辑：属于 v1 硬边界之外的结构字段";
        };
    }

    private static void checkFields(List<String> errors, String label, String field,
                                    List<String> values, Set<String> known, Set<String> allowedExtras) {
        Set<String> illegal = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || (!known.contains(value) && !allowedExtras.contains(value))) {
                illegal.add(String.valueOf(value));
            }
        }
        if (!illegal.isEmpty()) {
            errors.add(label + " 的 " + field + " 引用了不存在的字段：" + String.join("、", illegal)
                    + "；该类型既有字段：" + String.join("、", known));
        }
    }

    // ── 内部：影响评估与落盘 ────────────────────────────────────────────────

    /** 影响评估：标题模板变化需重启 + 全量重建，其余展示字段重启即生效。 */
    private Map<String, Object> impact(String objectTypeId, ObjectTypeDef current, ObjectTypeDef candidate) {
        boolean titleChanged = !Objects.equals(current.titleTemplate, candidate.titleTemplate);
        long objectCount = 0L;
        if (titleChanged) {
            try {
                objectCount = repository.countObjects(objectTypeId, List.of(), null, Map.of());
            } catch (Exception e) {
                log.warn("统计对象类型 {} 的对象数失败（影响评估按 0 处理）：{}", objectTypeId, e.getMessage());
            }
        }
        return Resp.map(
                "restartRequired", true,
                "fullRebuildRequired", titleChanged,
                "objectCount", objectCount,
                "scope", titleChanged
                        ? "titleTemplate 修改：需重启 + 全量重建才会重算已存对象 title"
                        : "展示字段修改：重启后即生效，无需重建投影",
                "hint", titleChanged
                        ? "该类型当前有 " + objectCount + " 个对象，应用后可点「重建当前投影」重算标题"
                        : "保存为草稿后点击「应用到运行时」（服务重启约 5–10 秒）即可看到新展示");
    }

    /** 序列化 → 回解自检 → 算版本 → 原子写对象 overlay → 登记 PENDING。 */
    private Map<String, Object> save(ModelDocument.ObjectTypeDocument document, String sourceId,
                                     ObjectEditRequest request, String note, Map<String, Object> impact) {
        String text = serialize(document);
        String pendingVersion = DefinitionDraftHash.versionForObjectText(
                sourceId, loader, text, relationStore);
        fileStore.writeOverlay(text);
        String operator = blankToDefault(request.operator, DEFAULT_OPERATOR);
        try {
            repository.markPendingMappingVersion(sourceId, pendingVersion,
                    DefinitionDraftHash.definitionHashForObjectText(loader, text, relationStore),
                    operator, note, SOURCE_OVERLAY);
            repository.supersedeOtherPending(sourceId, pendingVersion);
        } catch (Exception e) {
            log.warn("对象在线定义已写入但版本登记失败：{}", e.getMessage());
            throw new IllegalStateException("对象在线定义已写入，但版本登记失败：" + e.getMessage(), e);
        }
        log.info("对象类型定义已保存为草稿：version={} note={} operator={}", pendingVersion, note, operator);
        return Resp.map("saved", true, "applied", false,
                "pendingMappingVersion", pendingVersion,
                "definitionSource", SOURCE_OVERLAY,
                "operator", operator, "note", note, "impact", impact,
                "message", "已保存草稿，需点击『应用到运行时』后生效（本次修改重启即可生效）");
    }

    /** 序列化为 YAML 并立刻回解，确认写出的是一份自己能读回来的完整定义。 */
    private String serialize(ModelDocument.ObjectTypeDocument document) {
        YAMLMapper mapper = yaml();
        String text;
        try {
            text = mapper.writeValueAsString(document);
        } catch (Exception e) {
            throw new IllegalStateException("对象类型定义序列化失败：" + e.getMessage(), e);
        }
        try {
            ModelDocument.ObjectTypeDocument parsed =
                    mapper.readValue(text, ModelDocument.ObjectTypeDocument.class);
            int expected = document.objectTypes == null ? 0 : document.objectTypes.size();
            int actual = parsed.objectTypes == null ? 0 : parsed.objectTypes.size();
            if (expected != actual) {
                throw new IllegalStateException("序列化自检失败：写入 " + expected
                        + " 个对象类型，回解得到 " + actual + " 个");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("对象类型定义序列化自检失败：" + e.getMessage(), e);
        }
        return text;
    }

    private static YAMLMapper yaml() {
        YAMLMapper mapper = new YAMLMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false);
        mapper.configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, false);
        return mapper;
    }

    /**
     * 编辑基底：对象 overlay 存在时以它为基，否则取当前生效文档的深拷贝。
     *
     * <p>同一进程内保存的草稿不进 registry，因此二次编辑必须读 overlay 文件，
     * 否则会把上一份草稿的改动覆盖掉。</p>
     */
    private ModelDocument.ObjectTypeDocument draftDocument() {
        if (fileStore.overlayExists()) {
            try {
                ModelDocument.ObjectTypeDocument parsed = yaml().readValue(
                        fileStore.readOverlayText(), ModelDocument.ObjectTypeDocument.class);
                if (parsed != null && parsed.objectTypes != null) {
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("读取对象类型草稿失败，回退到当前生效定义：{}", e.getMessage());
            }
        }
        return currentDocument();
    }

    private ModelDocument.ObjectTypeDocument currentDocument() {
        ModelDocument.ObjectTypeDocument source = loader.objectDocument();
        ModelDocument.ObjectTypeDocument copy = new ModelDocument.ObjectTypeDocument();
        copy.version = source.version;
        for (ObjectTypeDef def : source.objectTypes) {
            copy.objectTypes.add(copyForSave(def));
        }
        return copy;
    }

    /** 对外视图：字段列表只给名字，属性 / 派生属性给完整可编辑结构。 */
    public static List<Map<String, Object>> objectViews(ModelDocument.ObjectTypeDocument document) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ObjectTypeDef def : document.objectTypes) {
            if (def == null) {
                continue;
            }
            ObjectTypeDef.IdentityRule identity = def.identity == null
                    ? new ObjectTypeDef.IdentityRule() : def.identity;
            List<Map<String, Object>> attributes = new ArrayList<>();
            for (AttributeDef attr : def.attributes == null ? List.<AttributeDef>of() : def.attributes) {
                if (attr == null) {
                    continue;
                }
                attributes.add(Resp.map("name", attr.name, "displayName", attr.displayName,
                        "type", attr.type, "required", attr.required, "unique", attr.unique,
                        "validate", attr.validate, "from", attr.from));
            }
            List<Map<String, Object>> derived = new ArrayList<>();
            for (DerivedPropertyDef item : def.derivedProperties == null
                    ? List.<DerivedPropertyDef>of() : def.derivedProperties) {
                if (item == null) {
                    continue;
                }
                derived.add(Resp.map("name", item.name, "displayName", item.displayName,
                        "type", item.type, "provider", item.provider, "resolver", item.resolver));
            }
            rows.add(Resp.map(
                    "id", def.id,
                    "displayName", def.displayName,
                    "description", def.description,
                    "titleTemplate", def.titleTemplate,
                    "listFields", def.listFields == null ? List.of() : def.listFields,
                    "searchableFields", def.searchableFields == null ? List.of() : def.searchableFields,
                    "filterableFields", def.filterableFields == null ? List.of() : def.filterableFields,
                    "identity", Resp.map("sourceId", identity.sourceId, "sourceTable", identity.sourceTable,
                            "sourcePk", identity.sourcePk, "globalIdRule", identity.globalIdRule),
                    "attributes", attributes,
                    "derivedProperties", derived,
                    "dynamicResolvers", def.dynamicResolvers == null ? List.of() : def.dynamicResolvers,
                    "sourceMappingRef", def.sourceMappingRef));
        }
        return rows;
    }

    private static ObjectTypeDef find(ModelDocument.ObjectTypeDocument document, String objectTypeId) {
        for (ObjectTypeDef def : document.objectTypes) {
            if (def != null && objectTypeId != null && objectTypeId.equals(def.id)) {
                return def;
            }
        }
        return null;
    }

    private static void replace(ModelDocument.ObjectTypeDocument document, String objectTypeId,
                                ObjectTypeDef replacement) {
        for (int i = 0; i < document.objectTypes.size(); i++) {
            ObjectTypeDef def = document.objectTypes.get(i);
            if (def != null && objectTypeId.equals(def.id)) {
                document.objectTypes.set(i, replacement);
                return;
            }
        }
        document.objectTypes.add(replacement);
    }

    /** 稳定比较用：剔除六个允许字段后，按 Jackson 字段顺序生成不可变指纹。 */
    private static Map<String, Object> fingerprint(ObjectTypeDef def) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) JSON.convertValue(def, Map.class);
        map.keySet().removeAll(EDITABLE_KEYS);
        return new LinkedHashMap<>(map);
    }

    private static ObjectTypeDef copyForSave(ObjectTypeDef source) {
        ObjectTypeDef target = new ObjectTypeDef();
        target.id = source.id;
        target.displayName = source.displayName;
        target.description = source.description;
        target.titleTemplate = source.titleTemplate;
        target.listFields = new ArrayList<>(source.listFields == null ? List.of() : source.listFields);
        target.searchableFields = new ArrayList<>(source.searchableFields == null
                ? List.of() : source.searchableFields);
        target.filterableFields = new ArrayList<>(source.filterableFields == null
                ? List.of() : source.filterableFields);
        target.sourceMappingRef = source.sourceMappingRef;
        if (source.identity != null) {
            ObjectTypeDef.IdentityRule identity = new ObjectTypeDef.IdentityRule();
            identity.sourceId = source.identity.sourceId;
            identity.sourceTable = source.identity.sourceTable;
            identity.sourcePk = source.identity.sourcePk;
            identity.globalIdRule = source.identity.globalIdRule;
            target.identity = identity;
        }
        for (AttributeDef attr : source.attributes == null ? List.<AttributeDef>of() : source.attributes) {
            target.attributes.add(copyOf(attr));
        }
        for (DerivedPropertyDef derived : source.derivedProperties == null
                ? List.<DerivedPropertyDef>of() : source.derivedProperties) {
            target.derivedProperties.add(copyOf(derived));
        }
        for (String resolver : source.dynamicResolvers == null ? List.<String>of() : source.dynamicResolvers) {
            target.dynamicResolvers.add(resolver);
        }
        return target;
    }

    private static AttributeDef copyOf(AttributeDef source) {
        AttributeDef target = new AttributeDef();
        if (source == null) {
            return target;
        }
        target.name = source.name;
        target.type = source.type;
        target.displayName = source.displayName;
        target.required = source.required;
        target.unique = source.unique;
        target.validate = source.validate;
        target.from = source.from;
        return target;
    }

    private static DerivedPropertyDef copyOf(DerivedPropertyDef source) {
        DerivedPropertyDef target = new DerivedPropertyDef();
        if (source == null) {
            return target;
        }
        target.name = source.name;
        target.type = source.type;
        target.displayName = source.displayName;
        target.provider = source.provider;
        target.resolver = source.resolver;
        return target;
    }

    // ── 内部：闸门与工具 ────────────────────────────────────────────────────

    private void guardBaseVersion(ObjectEditRequest request, String sourceId) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (request.baseMappingVersion == null || request.baseMappingVersion.isBlank()) {
            throw ApiException.badRequest("baseMappingVersion 不能为空：请刷新页面后重试");
        }
        String active = activeVersion(sourceId);
        if (!active.equals(request.baseMappingVersion)) {
            throw new ApiException(409, "VERSION_CONFLICT",
                    "生效版本已变化（当前 " + active + "，请求基于 " + request.baseMappingVersion
                            + "），请刷新页面后重试");
        }
    }

    private void guardTaskConflict() {
        boolean running = false;
        try {
            running = repository.hasRunningTask("FULL_IMPORT") || repository.hasRunningTask("REBUILD");
        } catch (Exception e) {
            log.warn("检查导入/重建任务状态失败，按无任务处理：{}", e.getMessage());
        }
        if (running) {
            throw new ApiException(409, "TASK_CONFLICT",
                    "当前有全量导入或重建任务正在运行，请等任务结束后再修改对象类型定义");
        }
    }

    /** 校验失败统一带 objectTypeId，与关系侧的 data.errors[] 形状对齐。 */
    private void failOnErrors(List<String> errors, String objectTypeId) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("objectTypeId", objectTypeId == null ? "" : objectTypeId);
        data.put("errors", List.copyOf(errors));
        throw new ApiException(400, "VALIDATION_FAILED", "对象类型校验未通过", data);
    }

    private String activeVersion(String sourceId) {
        return firstText(safeActiveRow(sourceId).get("mapping_version"),
                loader.mappingVersion().mappingVersion);
    }

    private Map<String, Object> safeActiveRow(String sourceId) {
        try {
            return repository.activeMappingVersion(sourceId);
        } catch (Exception e) {
            log.warn("读取当前生效版本失败，回退内存版本：{}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> safePendingRow(String sourceId) {
        try {
            return repository.latestPendingMappingVersion(sourceId);
        } catch (Exception e) {
            log.warn("读取草稿版本失败：{}", e.getMessage());
            return Map.of();
        }
    }

    private String sourceId() {
        return loader.registry().sourceId();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstText(Object value, String fallback) {
        String text = OntologyRepository.str(value);
        return text == null || text.isBlank() ? fallback : text;
    }
}
