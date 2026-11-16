package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.school.ontology.common.ApiException;
import com.school.ontology.common.Resp;
import com.school.ontology.repository.OntologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系类型在线编辑的编排层（07 增量设计 §6 / §12-S5）。
 *
 * <p>一次保存的完整动作固定为六步，顺序不可换：</p>
 * <ol>
 *   <li>乐观版本闸门（{@code baseMappingVersion} 必须等于当前 ACTIVE）与任务闸门；</li>
 *   <li>在<b>当前生效</b>的关系文档副本上做增 / 改 / 删（不碰 registry 里的对象）；</li>
 *   <li>字段级校验 → 集成级校验（与启动同一套 {@code validate}）；</li>
 *   <li>序列化 YAML 并<b>回解自检</b>（防止写出一份自己都读不回来的 overlay）；</li>
 *   <li>算新 hash / 新 mappingVersion，原子写 overlay；</li>
 *   <li>登记 PENDING 版本并把其它草稿置 SUPERSEDED。</li>
 * </ol>
 *
 * <p>保存<b>不等于</b>生效：真正换定义靠进程重启（{@link DefinitionApplyService}），
 * 因此这里不修改 {@code ModelRegistry}，也不改 {@code ont_model_def} 的生效内容。</p>
 */
@Component
public class RelationTypeDefinitionEditor {

    /** 与原业务 {@code change_records.operator} 一致的默认操作人。 */
    public static final String DEFAULT_OPERATOR = "演示管理员";
    private static final String SOURCE_OVERLAY = "OVERLAY";
    private static final String SOURCE_BASELINE = "BASELINE";
    private static final Logger log = LoggerFactory.getLogger(RelationTypeDefinitionEditor.class);

    private final ModelDefinitionLoader loader;
    private final RelationTypeValidator validator;
    private final DefinitionFileStore fileStore;
    private final OntologyRepository repository;

    public RelationTypeDefinitionEditor(ModelDefinitionLoader loader, RelationTypeValidator validator,
                                        DefinitionFileStore fileStore, OntologyRepository repository) {
        this.loader = loader;
        this.validator = validator;
        this.fileStore = fileStore;
        this.repository = repository;
    }

    // ── 增 / 改 / 删 / 恢复基线 ─────────────────────────────────────────────

    /** 新增一条关系类型（§6.1）。 */
    public Map<String, Object> add(RelationEditRequest request) {
        String sourceId = sourceId();
        guardBaseVersion(request, sourceId);
        guardTaskConflict();
        RelationTypeDef candidate = request.relation;
        ModelDocument.RelationTypeDocument document = draftDocument();
        List<String> errors = validator.validateFields(candidate, true, null, idsOf(document));
        if (errors.isEmpty()) {
            document.relationTypes.add(copy(candidate));
            errors = validator.validateIntegration(document);
        }
        failOnErrors(errors, candidate == null ? null : candidate.id);
        return save(document, sourceId, request,
                noteOrDefault(request, "新增关系类型 " + candidate.id));
    }

    /** 修改一条已存在的关系类型（§6.2）：id 不可改名，视为「删 + 增」。 */
    public Map<String, Object> update(String relationId, RelationEditRequest request) {
        String sourceId = sourceId();
        guardBaseVersion(request, sourceId);
        guardTaskConflict();
        ModelDocument.RelationTypeDocument document = draftDocument();
        Set<String> ids = idsOf(document);
        if (relationId == null || !ids.contains(relationId)) {
            throw ApiException.notFound("关系类型不存在：" + relationId);
        }
        RelationTypeDef candidate = request.relation;
        List<String> errors = validator.validateFields(candidate, false, relationId, ids);
        if (errors.isEmpty()) {
            replace(document, relationId, copy(candidate));
            errors = validator.validateIntegration(document);
        }
        failOnErrors(errors, relationId);
        return save(document, sourceId, request,
                noteOrDefault(request, "编辑关系类型 " + relationId));
    }

    /**
     * 删除一条关系类型（§6.3）。
     *
     * <p>先做引用影响分析：被来源映射引用时，未带 {@code force} 返回 409 影响清单；
     * 带 {@code force} 时**拒绝落盘**并说明原因（见下方注释）——因为「来源映射仍引用
     * 一个已被删除的关系类型」会让生效定义在下次启动校验时整体非法，
     * 触发回退基线（改动静默失效）且重建时 {@code registry.relationOf} 会抛错。
     * 这属于设计文档与既有实现的事实冲突，已如实回报主理人。</p>
     */
    public Map<String, Object> delete(String relationId, RelationEditRequest request) {
        String sourceId = sourceId();
        guardBaseVersion(request, sourceId);
        guardTaskConflict();
        ModelDocument.RelationTypeDocument document = draftDocument();
        Set<String> ids = idsOf(document);
        if (relationId == null || !ids.contains(relationId)) {
            throw ApiException.notFound("关系类型不存在：" + relationId);
        }
        RelationTypeValidator.DeleteImpact impact =
                validator.analyzeDelete(relationId, find(document, relationId));
        if (!impact.referencedByMappings().isEmpty()) {
            if (!request.force) {
                throw new ApiException(409, "DELETE_IMPACT_CONFIRM",
                        "删除会影响既有映射，需确认", impactData(impact));
            }
            throw new ApiException(409, "DELETE_BLOCKED_BY_MAPPING",
                    "该关系仍被 " + impact.referencedByMappings().size()
                            + " 处来源映射引用，删除后生效定义会在启动校验时整体非法"
                            + "（服务将回退打包基线、改动不生效，重建也会失败）；"
                            + "请先在 school-source.yaml 中移除对应 relationRules/joinRelationRules 再删除",
                    impactData(impact));
        }
        document.relationTypes.removeIf(def -> def != null && relationId.equals(def.id));
        List<String> errors = validator.validateIntegration(document);
        failOnErrors(errors, relationId);
        return save(document, sourceId, request, noteOrDefault(request, "删除关系类型 " + relationId));
    }

    /** 恢复打包基线（§6.4）：删除 overlay，回到 git 里的 relation-types.yaml。 */
    public Map<String, Object> reset(RelationEditRequest request) {
        String sourceId = sourceId();
        guardBaseVersion(request, sourceId);
        if (!request.confirm) {
            throw ApiException.badRequest("恢复基线会删除全部在线改动，需要二次确认（confirm=true）");
        }
        if (!fileStore.overlayExists()) {
            return Resp.map("reset", true, "noChange", true, "applied", false,
                    "definitionSource", SOURCE_BASELINE, "pendingMappingVersion", null,
                    "message", "当前没有在线改动，定义已在打包基线上");
        }
        String operator = blankToDefault(request.operator, DEFAULT_OPERATOR);
        String note = blankToDefault(request.note, "恢复打包基线");
        fileStore.deleteOverlay();
        // 草稿随 overlay 一起消失：把残留 PENDING 行置为 SUPERSEDED，避免版本表出现「幽灵草稿」；
        // 同时把「回到基线」这件事登记成一条待生效版本（PENDING，issuer 是执行恢复的人），
        // 这样重启激活时就能把署名落到基线那条 ACTIVE 记录上（P6：不能退回 system）。
        String baselineVersion = loader.baselineMappingVersion().mappingVersion;
        try {
            if (baselineVersion != null && !baselineVersion.isBlank()) {
                repository.markPendingMappingVersion(sourceId, baselineVersion,
                        loader.baselineMappingVersion().definitionHash, operator, note, SOURCE_BASELINE);
            }
            repository.supersedeOtherPending(sourceId, baselineVersion);
        } catch (Exception e) {
            log.warn("恢复基线后登记版本失败（文件已回退，激活时会重新解析署名）：{}", e.getMessage());
        }
        return Resp.map("reset", true, "applied", false,
                "definitionSource", SOURCE_BASELINE,
                "pendingMappingVersion", baselineVersion,
                "operator", operator, "note", note,
                "message", "已删除在线改动，将回到打包基线；点击『应用到运行时』后生效");
    }

    // ── 版本与草稿状态（§6.5）──────────────────────────────────────────────

    /** {@code GET /model/versions} 的完整负载。 */
    public Map<String, Object> versions() {
        String sourceId = sourceId();
        Map<String, Object> activeRow = safeActiveRow(sourceId);
        String activeVersion = firstText(activeRow.get("mapping_version"),
                loader.mappingVersion().mappingVersion);
        String activeSource = firstText(activeRow.get("definition_source"), loader.definitionSource());

        String draftVersion = draftVersion(sourceId);
        String pendingTarget = draftVersion == null
                ? loader.baselineMappingVersion().mappingVersion : draftVersion;
        Map<String, Object> pendingRow = safePendingRow(sourceId);
        String draftSavedAt = pendingRow.isEmpty()
                ? null : Resp.ts(OntologyRepository.dateTime(pendingRow.get("activated_at")));
        // 「谁在什么时候保存的草稿」（P6 要求 4）：operator/note 直接取自那条 PENDING 行。
        String draftSavedBy = pendingRow.isEmpty()
                ? null : OntologyRepository.str(pendingRow.get("operator"));
        String draftSavedNote = pendingRow.isEmpty()
                ? null : OntologyRepository.str(pendingRow.get("note"));
        // 「恢复基线」之后没有 overlay 文件，但待生效目标变成基线版本，仍要提示「待生效」。
        boolean pendingApply = pendingTarget != null && !pendingTarget.equals(activeVersion);

        List<Map<String, Object>> versions = new ArrayList<>();
        for (Map<String, Object> row : safeVersions(sourceId)) {
            versions.add(Resp.map(
                    "mappingVersion", OntologyRepository.str(row.get("mapping_version")),
                    "definitionHash", OntologyRepository.str(row.get("definition_hash")),
                    "status", OntologyRepository.str(row.get("status")),
                    "definitionSource", OntologyRepository.str(row.get("definition_source")),
                    "operator", OntologyRepository.str(row.get("operator")),
                    "note", OntologyRepository.str(row.get("note")),
                    "activatedAt", Resp.ts(OntologyRepository.dateTime(row.get("activated_at")))));
        }
        DefinitionFileStore.Fallback fallback = fileStore.lastFallback();
        return Resp.map(
                "sourceId", sourceId,
                "activeMappingVersion", activeVersion,
                "activeDefinitionSource", activeSource,
                "activeDefinitionHash", "sha256:" + loader.definitionHash(),
                // 当前生效版本是谁签字生效的（P6 要求 4：每条版本都要能回答 operator/note）。
                "activeOperator", firstText(activeRow.get("operator"),
                        DefinitionVersionAttribution.SYSTEM_OPERATOR),
                "activeNote", firstText(activeRow.get("note"),
                        DefinitionVersionAttribution.startupNote(activeSource)),
                "baselineMappingVersion", loader.baselineMappingVersion().mappingVersion,
                "draft", Resp.map(
                        "exists", fileStore.overlayExists(),
                        "draftMappingVersion", draftVersion,
                        "draftDefinitionSource", fileStore.overlayExists() ? SOURCE_OVERLAY : null,
                        "savedAt", draftSavedAt,
                        "savedBy", draftSavedBy,
                        "savedNote", draftSavedNote,
                        "pendingApply", pendingApply,
                        "pendingTargetVersion", pendingApply ? pendingTarget : null,
                        "pendingTargetSource", pendingApply
                                ? (fileStore.overlayExists() ? SOURCE_OVERLAY : SOURCE_BASELINE) : null,
                        // 草稿的完整关系清单：管理页用它渲染「待生效」的列表，
                        // 也让「刚新增还没生效」的关系在同一进程里可继续编辑/删除。
                        "relations", fileStore.overlayExists()
                                ? relationViews(draftDocument()) : List.of()),
                "lastFallback", Resp.map(
                        "occurred", fallback.occurred(),
                        "reason", fallback.reason(),
                        "at", Resp.ts(fallback.at())),
                "versions", versions);
    }

    /** 当前生效版本：优先取库里最后一条 ACTIVE，取不到（首次启动/库不可达）用内存版本。 */
    public String activeVersion(String sourceId) {
        return firstText(safeActiveRow(sourceId).get("mapping_version"),
                loader.mappingVersion().mappingVersion);
    }

    /** 当前草稿版本：直接从 overlay 文本算 hash，是「重启后真正会生效」的那个版本。 */
    public String draftVersion(String sourceId) {
        if (!fileStore.overlayExists()) {
            return null;
        }
        try {
            String overlayText = fileStore.readOverlayText();
            return versionOf(sourceId, Resp.sha256(loader.objectText() + "\n" + overlayText
                    + "\n" + loader.sourceText()));
        } catch (Exception e) {
            log.warn("读取在线定义以计算草稿版本失败：{}", e.getMessage());
            return null;
        }
    }

    // ── 内部：落盘与登记 ────────────────────────────────────────────────────

    /** 序列化 → 回解自检 → 算版本 → 原子写 overlay → 登记 PENDING。 */
    private Map<String, Object> save(ModelDocument.RelationTypeDocument document, String sourceId,
                                     RelationEditRequest request, String note) {
        String text = serialize(document);
        String hash = Resp.sha256(loader.objectText() + "\n" + text + "\n" + loader.sourceText());
        String pendingVersion = versionOf(sourceId, hash);
        fileStore.writeOverlay(text);
        String operator = blankToDefault(request.operator, DEFAULT_OPERATOR);
        try {
            // 保存即登记（P6）：同一草稿重复保存覆盖这一行（同一 version 幂等），
            // 不堆一堆 PENDING；署名取本次请求的 operator/note。
            repository.markPendingMappingVersion(sourceId, pendingVersion, "sha256:" + hash,
                    operator, note, SOURCE_OVERLAY);
            repository.supersedeOtherPending(sourceId, pendingVersion);
        } catch (Exception e) {
            // 文件已写入但版本登记失败：如实报错，避免「页面说保存成功、版本表里查不到」。
            log.warn("在线定义已写入但版本登记失败：{}", e.getMessage());
            throw new IllegalStateException("在线定义已写入，但版本登记失败：" + e.getMessage(), e);
        }
        log.info("关系类型定义已保存为草稿：version={} note={} operator={}", pendingVersion, note, operator);
        return Resp.map("saved", true, "applied", false,
                "pendingMappingVersion", pendingVersion,
                "definitionSource", SOURCE_OVERLAY,
                "operator", operator, "note", note,
                "message", "已保存草稿，点击『应用到运行时』后生效");
    }

    /** 把文档序列化为 YAML，并立刻回解一次确认可读（宁可在此报错，也不写坏文件）。 */
    private String serialize(ModelDocument.RelationTypeDocument document) {
        YAMLMapper mapper = yaml();
        String text;
        try {
            text = mapper.writeValueAsString(document);
        } catch (Exception e) {
            throw new IllegalStateException("关系类型定义序列化失败：" + e.getMessage(), e);
        }
        try {
            ModelDocument.RelationTypeDocument parsed =
                    mapper.readValue(text, ModelDocument.RelationTypeDocument.class);
            int expected = document.relationTypes == null ? 0 : document.relationTypes.size();
            int actual = parsed.relationTypes == null ? 0 : parsed.relationTypes.size();
            if (expected != actual) {
                throw new IllegalStateException("序列化自检失败：写入 " + expected
                        + " 条关系类型，回解得到 " + actual + " 条");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("关系类型定义序列化自检失败：" + e.getMessage(), e);
        }
        return text;
    }

    private static YAMLMapper yaml() {
        YAMLMapper mapper = new YAMLMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 不写 null 字段：回解时字段初始化值（如 validFromColumns=[]）不会被 null 覆盖。
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false);
        mapper.configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, false);
        return mapper;
    }

    /**
     * 编辑基底文档：**草稿优先**（overlay 存在时以它为基），否则取当前生效定义。
     *
     * <p>为什么不是直接用 {@code loader.relationDocument()}：同一进程内保存过的草稿
     * 不会进入 {@code ModelRegistry}（本方案靠重启生效），以 registry 为基会导致
     * 「刚存的新关系改不了、删不掉」，也会把上一份草稿的改动覆盖掉。</p>
     */
    private ModelDocument.RelationTypeDocument draftDocument() {
        if (fileStore.overlayExists()) {
            try {
                ModelDocument.RelationTypeDocument parsed = yaml().readValue(
                        fileStore.readOverlayText(), ModelDocument.RelationTypeDocument.class);
                if (parsed != null && parsed.relationTypes != null) {
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("读取草稿定义失败，回退到当前生效定义：{}", e.getMessage());
            }
        }
        return currentDocument();
    }

    /** 当前生效关系文档的深拷贝：编辑期间绝不修改 registry 里被 37 处引用的对象。 */
    private ModelDocument.RelationTypeDocument currentDocument() {
        ModelDocument.RelationTypeDocument source = loader.relationDocument();
        ModelDocument.RelationTypeDocument copy = new ModelDocument.RelationTypeDocument();
        copy.version = source.version;
        for (RelationTypeDef def : source.relationTypes) {
            copy.relationTypes.add(copy(def));
        }
        return copy;
    }

    /** 关系定义的对外视图（与 {@code GET /model/relations} 的条目同形，供草稿列表复用）。 */
    private static List<Map<String, Object>> relationViews(ModelDocument.RelationTypeDocument document) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RelationTypeDef def : document.relationTypes) {
            if (def == null) {
                continue;
            }
            GenerationRule generation = def.generation == null ? new GenerationRule() : def.generation;
            rows.add(Resp.map(
                    "id", def.id,
                    "displayName", def.displayName,
                    "fromType", def.fromType,
                    "toType", def.toType,
                    "forwardName", def.forwardName,
                    "reverseName", def.reverseName,
                    "direction", def.direction,
                    "cardinality", def.cardinality,
                    "businessEffectiveTime", def.businessEffectiveTime,
                    "generation", Resp.map(
                            "kind", generation.kind,
                            "sourceTable", generation.sourceTable,
                            "fkColumn", generation.fkColumn,
                            "targetTable", generation.targetTable,
                            "rule", generation.rule,
                            "distinct", generation.distinct,
                            "sourceColumn", generation.sourceColumn,
                            "joinTable", generation.joinTable,
                            "joinFilterColumn", generation.joinFilterColumn,
                            "joinSelectColumn", generation.joinSelectColumn,
                            "validFromColumns", generation.validFromColumns),
                    "sourceMappingRef", def.sourceMappingRef));
        }
        return rows;
    }

    private static Set<String> idsOf(ModelDocument.RelationTypeDocument document) {
        Set<String> ids = new LinkedHashSet<>();
        for (RelationTypeDef def : document.relationTypes) {
            if (def != null && def.id != null && !def.id.isBlank()) {
                ids.add(def.id);
            }
        }
        return ids;
    }

    private static RelationTypeDef find(ModelDocument.RelationTypeDocument document, String relationId) {
        for (RelationTypeDef def : document.relationTypes) {
            if (def != null && relationId.equals(def.id)) {
                return def;
            }
        }
        return null;
    }

    private static void replace(ModelDocument.RelationTypeDocument document, String relationId,
                                RelationTypeDef replacement) {
        for (int i = 0; i < document.relationTypes.size(); i++) {
            RelationTypeDef def = document.relationTypes.get(i);
            if (def != null && relationId.equals(def.id)) {
                document.relationTypes.set(i, replacement);
                return;
            }
        }
        document.relationTypes.add(replacement);
    }

    private static RelationTypeDef copy(RelationTypeDef source) {
        if (source == null) {
            return null;
        }
        RelationTypeDef target = new RelationTypeDef();
        target.id = source.id;
        target.displayName = source.displayName;
        target.fromType = source.fromType;
        target.toType = source.toType;
        target.forwardName = source.forwardName;
        target.reverseName = source.reverseName;
        target.direction = source.direction;
        target.cardinality = source.cardinality;
        target.businessEffectiveTime = source.businessEffectiveTime;
        target.sourceMappingRef = source.sourceMappingRef;
        if (source.generation != null) {
            GenerationRule rule = new GenerationRule();
            rule.kind = source.generation.kind;
            rule.sourceTable = source.generation.sourceTable;
            rule.fkColumn = source.generation.fkColumn;
            rule.targetTable = source.generation.targetTable;
            rule.rule = source.generation.rule;
            rule.distinct = source.generation.distinct;
            rule.sourceColumn = source.generation.sourceColumn;
            rule.joinTable = source.generation.joinTable;
            rule.joinFilterColumn = source.generation.joinFilterColumn;
            rule.joinSelectColumn = source.generation.joinSelectColumn;
            rule.validFromColumns = source.generation.validFromColumns == null
                    ? new ArrayList<>() : new ArrayList<>(source.generation.validFromColumns);
            target.generation = rule;
        }
        return target;
    }

    // ── 内部：闸门与工具 ────────────────────────────────────────────────────

    /** 乐观版本闸门：请求里的 {@code baseMappingVersion} 必须等于当前 ACTIVE。 */
    private void guardBaseVersion(RelationEditRequest request, String sourceId) {
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

    /** 与导入 / 重建并发时拒绝编辑：避免「改定义的同时在重建」导致语义混乱。 */
    private void guardTaskConflict() {
        boolean running = false;
        try {
            running = repository.hasRunningTask("FULL_IMPORT") || repository.hasRunningTask("REBUILD");
        } catch (Exception e) {
            log.warn("检查导入/重建任务状态失败，按无任务处理：{}", e.getMessage());
        }
        if (running) {
            throw new ApiException(409, "TASK_CONFLICT",
                    "当前有全量导入或重建任务正在运行，请等任务结束后再修改定义");
        }
    }

    private void failOnErrors(List<String> errors, String relationId) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        throw ApiException.validationFailed("关系类型校验未通过", relationId, errors);
    }

    private Map<String, Object> impactData(RelationTypeValidator.DeleteImpact impact) {
        return Resp.map("relationId", impact.relationId(),
                "referencedByMappings", impact.referencedByMappings(),
                "impactedObjectTypes", impact.impactedObjectTypes(),
                "hint", impact.hint());
    }

    private String noteOrDefault(RelationEditRequest request, String defaultNote) {
        return blankToDefault(request == null ? null : request.note, defaultNote);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String sourceId() {
        return loader.registry().sourceId();
    }

    private String versionOf(String sourceId, String hash) {
        return sourceId + ":" + hash.substring(0, 12);
    }

    private static String firstText(Object value, String fallback) {
        String text = OntologyRepository.str(value);
        return text == null || text.isBlank() ? fallback : text;
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

    private List<Map<String, Object>> safeVersions(String sourceId) {
        try {
            return repository.mappingVersions(sourceId);
        } catch (Exception e) {
            log.warn("读取版本列表失败：{}", e.getMessage());
            return List.of();
        }
    }
}
