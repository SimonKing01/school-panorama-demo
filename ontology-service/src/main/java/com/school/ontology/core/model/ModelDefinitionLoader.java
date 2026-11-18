package com.school.ontology.core.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.school.ontology.common.Resp;
import com.school.ontology.core.spi.DerivedPropertyProvider;
import com.school.ontology.core.spi.DynamicResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * YAML 定义加载 + 强校验 + 映射版本计算（B1「非法定义加载报错」）。
 *
 * <p>刻意把加载放在构造函数里完成：Spring 构造本 Bean 时即可得到完整定义，
 * 其它 Bean 通过构造器注入拿到的一定是已校验对象，避免 @PostConstruct 顺序问题。
 * 任何校验失败都抛 {@link IllegalStateException}，服务启动即失败，不带着错误定义运行。</p>
 */
@Component
public class ModelDefinitionLoader {

    private static final String OBJECT_TYPES_FILE = "ontology/model/object-types.yaml";
    private static final String RELATION_TYPES_FILE = "ontology/model/relation-types.yaml";
    private static final String SOURCE_MAPPING_FILE = "ontology/mapping/school-source.yaml";

    private static final Set<String> ALLOWED_ATTRIBUTE_TYPES =
            Set.of("string", "int", "decimal", "boolean", "date", "time", "string[]");
    private static final Set<String> ALLOWED_GENERATION_KINDS = Set.of("FOREIGN_KEY", "JOIN_RULE");
    private static final Set<String> CORE_TRANSFORM_KEYS =
            Set.of("LOOKUP_NAME", "NESTED_LOOKUP_NAME", "DATE_yyyyMMdd", "TIME_HHmm");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final ModelDocument.ObjectTypeDocument objectDocument;
    private final ModelDocument.RelationTypeDocument relationDocument;
    private final ModelDocument.SourceMappingDocument sourceDocument;
    private final ModelRegistry registry;
    private final MappingVersion mappingVersion;
    private final String definitionHash;
    /** 实际生效的三份文本（hash 覆盖它们；overlay 存在时对应文本来自 overlay）。 */
    private final String effectiveObjectText;
    private final String effectiveRelationText;
    private final String sourceText;
    /** 打包基线原文（忽略 overlay）：算「恢复基线」目标版本要用。 */
    private final String baselineObjectText;
    private final String baselineRelationText;
    /** BASELINE / OVERLAY / OVERLAY_FALLBACK，用于管理页展示「生效定义从哪来」。 */
    private final String definitionSource;
    /** 对象 / 关系各自的定义来源，便于分别展示两份 overlay 的状态。 */
    private final String objectDefinitionSource;
    private final String relationDefinitionSource;
    /** 打包基线（忽略 overlay）的版本，供「恢复基线」判断是否已回到基线。 */
    private final MappingVersion baselineMappingVersion;

    /** 单元测试 / 无 Spring 场景：只读打包基线，没有 overlay。 */
    public ModelDefinitionLoader() {
        this(null, null);
    }

    /** 单 store 构造（既有关系测试零改动）：只有关系 overlay，没有对象 overlay。 */
    public ModelDefinitionLoader(DefinitionFileStore fileStore) {
        this(fileStore, null);
    }

    /**
     * 生产构造：先读三份打包基线，再依次尝试用对象 / 关系 overlay 覆盖。
     *
     * <p>两条闸门严格区分（07 增量设计 §5.3）：</p>
     * <ul>
     *   <li><b>基线非法</b> → 抛 {@link IllegalStateException} 拒绝启动（与 B1 口径一致）；</li>
     *   <li><b>overlay 非法</b> → 隔离坏文件 + 记录 OVERLAY_FALLBACK + 回退基线照常启动，
     *       任何情况下都不允许坏配置把服务锁死。</li>
     * </ul>
     *
     * <p>对象 overlay 的校验以「已生效的关系文档」为参照：关系 overlay 生效时，
     * 对象 overlay 必须同时满足新关系定义里的 fromType/toType 约束。</p>
     */
    @Autowired
    public ModelDefinitionLoader(
            @Qualifier("relationDefinitionFileStore") DefinitionFileStore relationStore,
            @Qualifier("objectDefinitionFileStore") DefinitionFileStore objectStore) {
        YAMLMapper mapper = new YAMLMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.baselineObjectText = readText(OBJECT_TYPES_FILE);
        this.baselineRelationText = readText(RELATION_TYPES_FILE);
        this.sourceText = readText(SOURCE_MAPPING_FILE);
        ModelDocument.ObjectTypeDocument baselineObjectDocument =
                parse(mapper, baselineObjectText, ModelDocument.ObjectTypeDocument.class, OBJECT_TYPES_FILE);
        ModelDocument.RelationTypeDocument baselineRelationDocument =
                parse(mapper, baselineRelationText, ModelDocument.RelationTypeDocument.class, RELATION_TYPES_FILE);
        this.sourceDocument = parse(mapper, sourceText, ModelDocument.SourceMappingDocument.class, SOURCE_MAPPING_FILE);

        // 闸门 1：打包基线非法 → 拒绝启动（绝不放宽）。
        List<String> baselineErrors = validate(baselineObjectDocument, baselineRelationDocument, sourceDocument);
        if (!baselineErrors.isEmpty()) {
            throw new IllegalStateException("本体模型定义非法，服务拒绝启动：\n - "
                    + String.join("\n - ", baselineErrors));
        }

        // 闸门 2a：关系 overlay 优先；非法则回退基线并留证，不影响启动。
        String effectiveRelationText = baselineRelationText;
        ModelDocument.RelationTypeDocument effectiveRelationDocument = baselineRelationDocument;
        String relationSource = "BASELINE";
        if (relationStore != null && relationStore.overlayExists()) {
            try {
                String overlayText = relationStore.readOverlayText();
                ModelDocument.RelationTypeDocument overlayDocument = parse(mapper, overlayText,
                        ModelDocument.RelationTypeDocument.class, "overlay:" + DefinitionFileStore.OVERLAY_FILE);
                List<String> overlayErrors = validate(baselineObjectDocument, overlayDocument, sourceDocument);
                if (!overlayErrors.isEmpty()) {
                    throw new OverlayInvalidException(String.join("；", overlayErrors));
                }
                effectiveRelationText = overlayText;
                effectiveRelationDocument = overlayDocument;
                relationSource = "OVERLAY";
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                relationStore.quarantineInvalidOverlay(reason);
                relationSource = "OVERLAY_FALLBACK";
            }
        }

        // 闸门 2b：对象 overlay 同理；参照上面的「已生效关系文档」做集成校验。
        String effectiveObjectText = baselineObjectText;
        ModelDocument.ObjectTypeDocument effectiveObjectDocument = baselineObjectDocument;
        String objectSource = "BASELINE";
        if (objectStore != null && objectStore.overlayExists()) {
            try {
                String overlayText = objectStore.readOverlayText();
                ModelDocument.ObjectTypeDocument overlayDocument = parse(mapper, overlayText,
                        ModelDocument.ObjectTypeDocument.class, "overlay:" + DefinitionFileStore.OBJECT_OVERLAY_FILE);
                List<String> overlayErrors = validate(overlayDocument, effectiveRelationDocument, sourceDocument);
                if (!overlayErrors.isEmpty()) {
                    throw new OverlayInvalidException(String.join("；", overlayErrors));
                }
                effectiveObjectText = overlayText;
                effectiveObjectDocument = overlayDocument;
                objectSource = "OVERLAY";
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                objectStore.quarantineInvalidOverlay(reason);
                objectSource = "OVERLAY_FALLBACK";
            }
        }
        this.effectiveObjectText = effectiveObjectText;
        this.effectiveRelationText = effectiveRelationText;
        this.objectDocument = effectiveObjectDocument;
        this.relationDocument = effectiveRelationDocument;
        this.objectDefinitionSource = objectSource;
        this.relationDefinitionSource = relationSource;
        this.definitionSource = "OVERLAY_FALLBACK".equals(objectSource) || "OVERLAY_FALLBACK".equals(relationSource)
                ? "OVERLAY_FALLBACK"
                : ("OVERLAY".equals(objectSource) || "OVERLAY".equals(relationSource) ? "OVERLAY" : "BASELINE");

        Map<String, ObjectTypeDef> types = new LinkedHashMap<>();
        for (ObjectTypeDef def : objectDocument.objectTypes) {
            types.put(def.id, def);
        }
        Map<String, RelationTypeDef> relations = new LinkedHashMap<>();
        for (RelationTypeDef def : relationDocument.relationTypes) {
            relations.put(def.id, def);
        }
        Map<String, ObjectMappingDef> mappings = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectMappingDef> entry : sourceDocument.objectMappings.entrySet()) {
            entry.getValue().objectType = entry.getKey();
            mappings.put(entry.getKey(), entry.getValue());
        }
        String sourceId = sourceDocument.dataSource.id;
        this.registry = new ModelRegistry(types, relations, mappings, sourceId);

        // hash 必须覆盖「实际生效」的三份文本：任一 overlay 改字段都要让版本变化。
        this.definitionHash = Resp.sha256(effectiveObjectText + "\n" + effectiveRelationText + "\n" + sourceText);
        this.mappingVersion = new MappingVersion(sourceId, sourceId + ":" + definitionHash.substring(0, 12),
                "sha256:" + definitionHash, LocalDateTime.now(), "ACTIVE");
        String baselineHash = Resp.sha256(baselineObjectText + "\n" + baselineRelationText + "\n" + sourceText);
        this.baselineMappingVersion = new MappingVersion(sourceId, sourceId + ":" + baselineHash.substring(0, 12),
                "sha256:" + baselineHash, LocalDateTime.now(), "ACTIVE");
    }

    public ModelRegistry registry() {
        return registry;
    }

    public MappingVersion mappingVersion() {
        return mappingVersion;
    }

    public String definitionHash() {
        return definitionHash;
    }

    /** 实际生效的定义来源：BASELINE / OVERLAY / OVERLAY_FALLBACK。 */
    public String definitionSource() {
        return definitionSource;
    }

    /** 生效的对象类型定义原文（overlay 存在时就是对象 overlay 文本）。 */
    public String objectText() {
        return effectiveObjectText;
    }

    /** 生效的对象类型定义原文（与 {@link #objectText()} 同义，命名与关系侧对齐）。 */
    public String effectiveObjectText() {
        return effectiveObjectText;
    }

    /** 生效的关系类型定义原文（overlay 存在时就是 overlay 文本）。 */
    public String effectiveRelationText() {
        return effectiveRelationText;
    }

    /** 生效的来源映射原文（hash 的组成部分；来源映射没有 overlay）。 */
    public String sourceText() {
        return sourceText;
    }

    /** 打包基线的对象类型原文：没有对象 overlay 时草稿 / 恢复基线的 hash 输入。 */
    public String baselineObjectText() {
        return baselineObjectText;
    }

    /** 打包基线的关系类型原文：没有关系 overlay 时草稿 / 恢复基线的 hash 输入。 */
    public String baselineRelationText() {
        return baselineRelationText;
    }

    /** 对象 overlay 各自的来源：BASELINE / OVERLAY / OVERLAY_FALLBACK。 */
    public String objectDefinitionSource() {
        return objectDefinitionSource;
    }

    /** 关系 overlay 各自的来源：BASELINE / OVERLAY / OVERLAY_FALLBACK。 */
    public String relationDefinitionSource() {
        return relationDefinitionSource;
    }

    /** 打包基线（忽略 overlay）对应的版本，「恢复基线」用它判断是否已在基线上。 */
    public MappingVersion baselineMappingVersion() {
        return baselineMappingVersion;
    }

    public ModelDocument.ObjectTypeDocument objectDocument() {
        return objectDocument;
    }

    public ModelDocument.RelationTypeDocument relationDocument() {
        return relationDocument;
    }

    public ModelDocument.SourceMappingDocument sourceDocument() {
        return sourceDocument;
    }

    /**
     * 强校验主入口。public 便于单元测试用「故意非法」的定义做负例验证，
     * 与运行时加载走的是同一套规则。
     */
    public List<String> validate(ModelDocument.ObjectTypeDocument objects,
                                 ModelDocument.RelationTypeDocument relations,
                                 ModelDocument.SourceMappingDocument source) {
        List<String> errors = new ArrayList<>();
        if (objects == null || objects.objectTypes == null || objects.objectTypes.isEmpty()) {
            errors.add("object-types.yaml 必须至少定义一个对象类型");
            return errors;
        }
        if (relations == null || relations.relationTypes == null) {
            errors.add("relation-types.yaml 缺少 relationTypes");
            return errors;
        }
        if (source == null || source.dataSource == null || source.objectMappings == null) {
            errors.add("school-source.yaml 缺少 dataSource 或 objectMappings");
            return errors;
        }

        String sourceId = source.dataSource.id;
        Set<String> typeIds = new LinkedHashSet<>();
        for (ObjectTypeDef def : objects.objectTypes) {
            String label = "对象类型[" + (def == null ? "null" : def.id) + "]";
            if (def == null || isBlank(def.id) || !IDENTIFIER.matcher(def.id).matches()) {
                errors.add(label + " 的 id 非法");
                continue;
            }
            if (!typeIds.add(def.id)) {
                errors.add(label + " 的 id 重复");
            }
            if (isBlank(def.displayName)) {
                errors.add(label + " 缺少 displayName");
            }
            if (isBlank(def.titleTemplate)) {
                errors.add(label + " 缺少 titleTemplate");
            }
            if (def.identity == null || isBlank(def.identity.sourceId)
                    || isBlank(def.identity.sourceTable) || isBlank(def.identity.sourcePk)) {
                errors.add(label + " 的稳定身份规则不完整");
            } else {
                if (!sourceId.equals(def.identity.sourceId)) {
                    errors.add(label + " 的 identity.sourceId 与 dataSource.id 不一致（跨源身份会串）");
                }
                if (!GlobalIds.GID_RULE.equals(def.identity.globalIdRule)) {
                    errors.add(label + " 的 globalIdRule 必须是 " + GlobalIds.GID_RULE);
                }
            }
            if (def.attributes == null || def.attributes.isEmpty()) {
                errors.add(label + " 必须定义属性");
            } else {
                Set<String> names = new HashSet<>();
                for (AttributeDef attr : def.attributes) {
                    if (attr == null || isBlank(attr.name) || isBlank(attr.displayName)) {
                        errors.add(label + " 存在缺少 name/displayName 的属性");
                        continue;
                    }
                    if (!names.add(attr.name)) {
                        errors.add(label + " 的属性 " + attr.name + " 重复");
                    }
                    if (!ALLOWED_ATTRIBUTE_TYPES.contains(attr.type)) {
                        errors.add(label + " 的属性 " + attr.name + " 类型 " + attr.type + " 不在允许枚举内");
                    }
                }
                Set<String> known = def.knownFieldNames();
                for (String field : nullSafe(def.searchableFields)) {
                    if (!known.contains(field)) {
                        errors.add(label + " 的可搜索字段 " + field + " 不是已定义属性/派生属性");
                    }
                }
                for (String field : nullSafe(def.listFields)) {
                    if (!known.contains(field)) {
                        errors.add(label + " 的列表字段 " + field + " 不是已定义属性/派生属性");
                    }
                }
                // 注意：filterableFields 在打包基线里允许包含来源列（如 schoolId/classId），
                // 它们由「filterableFields ∩ SOURCE 属性」白名单在查询层消费，不是展示属性。
                // 因此这里不做全局强校验，避免基线自身非法；在线编辑侧按「存量豁免、新增拒绝」管控。
            }
            // 标题模板的 {占位符} 必须是既有属性 / 派生属性，否则详情页会出现空标题。
            if (!isBlank(def.titleTemplate)) {
                var placeholders = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}").matcher(def.titleTemplate);
                while (placeholders.find()) {
                    String field = placeholders.group(1);
                    if (!def.knownFieldNames().contains(field)) {
                        errors.add(label + " 的 titleTemplate 引用了不存在的字段：" + field);
                    }
                }
            }
        }

        Set<String> providerKeys = new HashSet<>();
        for (ModelDocument.SpiBinding binding : nullSafe(source.providers)) {
            if (binding == null || isBlank(binding.key) || isBlank(binding.impl)) {
                errors.add("providers 存在缺少 key/impl 的注册项");
                continue;
            }
            if (!providerKeys.add(binding.key)) {
                errors.add("Provider key 重复：" + binding.key);
            }
            checkImplementation(errors, binding.impl, DerivedPropertyProvider.class, "Provider " + binding.key);
        }
        Set<String> resolverKeys = new HashSet<>();
        for (ModelDocument.SpiBinding binding : nullSafe(source.resolvers)) {
            if (binding == null || isBlank(binding.key) || isBlank(binding.impl)) {
                errors.add("resolvers 存在缺少 key/impl 的注册项");
                continue;
            }
            if (!resolverKeys.add(binding.key)) {
                errors.add("Resolver key 重复：" + binding.key);
            }
            checkImplementation(errors, binding.impl, DynamicResolver.class, "Resolver " + binding.key);
        }

        for (ObjectTypeDef def : objects.objectTypes) {
            if (def == null) {
                continue;
            }
            for (DerivedPropertyDef derived : nullSafe(def.derivedProperties)) {
                if (derived == null || isBlank(derived.name)) {
                    errors.add("对象类型[" + def.id + "] 存在缺少 name 的派生属性");
                    continue;
                }
                if (!ALLOWED_ATTRIBUTE_TYPES.contains(derived.type)) {
                    errors.add("对象类型[" + def.id + "] 派生属性 " + derived.name + " 类型非法");
                }
                if (isBlank(derived.provider) && isBlank(derived.resolver)) {
                    errors.add("对象类型[" + def.id + "] 派生属性 " + derived.name + " 必须指定 provider 或 resolver");
                }
                if (!isBlank(derived.provider) && !providerKeys.contains(derived.provider)) {
                    errors.add("对象类型[" + def.id + "] 引用了未注册的 Provider：" + derived.provider);
                }
                if (!isBlank(derived.resolver) && !resolverKeys.contains(derived.resolver)) {
                    errors.add("对象类型[" + def.id + "] 引用了未注册的 Resolver：" + derived.resolver);
                }
            }
            for (String key : nullSafe(def.dynamicResolvers)) {
                if (!resolverKeys.contains(key)) {
                    errors.add("对象类型[" + def.id + "] 引用了未注册的动态 Resolver：" + key);
                }
            }
        }

        Set<String> relationIds = new HashSet<>();
        for (RelationTypeDef def : relations.relationTypes) {
            String label = "关系类型[" + (def == null ? "null" : def.id) + "]";
            if (def == null || isBlank(def.id) || !IDENTIFIER.matcher(def.id).matches()) {
                errors.add(label + " 的 id 非法");
                continue;
            }
            if (!relationIds.add(def.id)) {
                errors.add(label + " 的 id 重复");
            }
            if (!typeIds.contains(def.fromType) || !typeIds.contains(def.toType)) {
                errors.add(label + " 的起止类型必须是已注册对象类型（from=" + def.fromType + ", to=" + def.toType + "）");
            }
            if (isBlank(def.forwardName) || isBlank(def.reverseName)) {
                errors.add(label + " 缺少正向/反向展示名");
            }
            if (def.generation == null || !ALLOWED_GENERATION_KINDS.contains(def.generation.kind)) {
                errors.add(label + " 的 generation.kind 必须是 FOREIGN_KEY 或 JOIN_RULE");
            } else if ("FOREIGN_KEY".equals(def.generation.kind)
                    && (isBlank(def.generation.sourceTable) || isBlank(def.generation.fkColumn))) {
                errors.add(label + " 的外键生成规则缺少 sourceTable/fkColumn");
            }
        }

        if (source.dataSource.watermark == null
                || isBlank(source.dataSource.watermark.table)
                || isBlank(source.dataSource.watermark.column)
                || !"SYSTEM_REVISION".equals(source.dataSource.watermark.kind)) {
            errors.add("数据源水位必须是 SYSTEM_REVISION 并指定 table/column");
        }
        if (isBlank(source.dataSource.healthCheckSql) || isBlank(source.dataSource.businessTimeSql)) {
            errors.add("数据源缺少 healthCheckSql / businessTimeSql");
        }
        for (Map.Entry<String, ObjectMappingDef> entry : source.objectMappings.entrySet()) {
            String objectType = entry.getKey();
            ObjectMappingDef mapping = entry.getValue();
            String label = "对象映射[" + objectType + "]";
            if (!typeIds.contains(objectType)) {
                errors.add(label + " 没有对应的对象类型定义");
            }
            if (mapping == null || isBlank(mapping.sourceTable) || isBlank(mapping.pk)) {
                errors.add(label + " 缺少 sourceTable / pk");
                continue;
            }
            if (mapping.stableId == null || !GlobalIds.GID_RULE.equals(mapping.stableId.rule)) {
                errors.add(label + " 的 stableId.rule 必须是 " + GlobalIds.GID_RULE);
            }
            for (FieldMappingDef fm : nullSafe(mapping.fieldMappings)) {
                if (fm == null || isBlank(fm.target) || isBlank(fm.source)) {
                    errors.add(label + " 存在缺少 target/source 的字段映射");
                    continue;
                }
                if (!isBlank(fm.transform) && !CORE_TRANSFORM_KEYS.contains(fm.transform)) {
                    errors.add(label + " 引用了未注册的字段转换：" + fm.transform);
                }
            }
            for (ObjectMappingDef.RelationRuleDef rule : nullSafe(mapping.relationRules)) {
                if (rule == null || isBlank(rule.relationType) || isBlank(rule.fk)
                        || isBlank(rule.targetObjectType)) {
                    errors.add(label + " 存在不完整的外键关系规则");
                    continue;
                }
                if (!relationIds.contains(rule.relationType)) {
                    errors.add(label + " 引用了未定义的关系类型：" + rule.relationType);
                }
                if (!typeIds.contains(rule.targetObjectType)) {
                    errors.add(label + " 引用了未定义的目标对象类型：" + rule.targetObjectType);
                }
            }
            for (ObjectMappingDef.JoinRelationRule rule : nullSafe(mapping.joinRelationRules)) {
                if (rule == null || isBlank(rule.relationType) || isBlank(rule.sourceColumn)
                        || isBlank(rule.joinTable) || isBlank(rule.joinFilterColumn)
                        || isBlank(rule.joinSelectColumn) || isBlank(rule.targetObjectType)) {
                    errors.add(label + " 存在不完整的联表关系规则");
                    continue;
                }
                if (!relationIds.contains(rule.relationType)) {
                    errors.add(label + " 引用了未定义的关系类型：" + rule.relationType);
                }
            }
            for (ObjectMappingDef.DerivedPropertyBinding binding : nullSafe(mapping.derivedProperties)) {
                if (binding == null || isBlank(binding.name)) {
                    errors.add(label + " 存在缺少 name 的派生属性绑定");
                    continue;
                }
                if (!isBlank(binding.provider) && !providerKeys.contains(binding.provider)) {
                    errors.add(label + " 引用了未注册的 Provider：" + binding.provider);
                }
                if (!isBlank(binding.resolver) && !resolverKeys.contains(binding.resolver)) {
                    errors.add(label + " 引用了未注册的 Resolver：" + binding.resolver);
                }
                if (isBlank(binding.provider) && isBlank(binding.resolver)) {
                    errors.add(label + " 的派生属性 " + binding.name + " 未绑定 provider/resolver");
                }
            }
        }
        return errors;
    }

    private static void checkImplementation(List<String> errors, String className,
                                           Class<?> expectedInterface, String label) {
        try {
            Class<?> type = Class.forName(className);
            if (!expectedInterface.isAssignableFrom(type)) {
                errors.add(label + " 的实现类未实现 " + expectedInterface.getSimpleName());
            }
        } catch (ClassNotFoundException e) {
            errors.add(label + " 的实现类不存在：" + className);
        }
    }

    private static <T> T parse(YAMLMapper mapper, String text, Class<T> type, String label) {
        try {
            return mapper.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析本体定义文件：" + label
                    + "（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）", e);
        }
    }

    private static String readText(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取本体定义文件：" + path, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <E> List<E> nullSafe(List<E> list) {
        return list == null ? List.of() : list;
    }
}
