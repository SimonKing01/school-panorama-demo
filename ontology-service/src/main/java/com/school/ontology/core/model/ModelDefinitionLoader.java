package com.school.ontology.core.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.school.ontology.common.Resp;
import com.school.ontology.core.spi.DerivedPropertyProvider;
import com.school.ontology.core.spi.DynamicResolver;
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

    public ModelDefinitionLoader() {
        YAMLMapper mapper = new YAMLMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectDocument = read(mapper, OBJECT_TYPES_FILE, ModelDocument.ObjectTypeDocument.class);
        this.relationDocument = read(mapper, RELATION_TYPES_FILE, ModelDocument.RelationTypeDocument.class);
        this.sourceDocument = read(mapper, SOURCE_MAPPING_FILE, ModelDocument.SourceMappingDocument.class);

        List<String> errors = validate(objectDocument, relationDocument, sourceDocument);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("本体模型定义非法，服务拒绝启动：\n - " + String.join("\n - ", errors));
        }

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

        this.definitionHash = Resp.sha256(readText(OBJECT_TYPES_FILE)
                + "\n" + readText(RELATION_TYPES_FILE)
                + "\n" + readText(SOURCE_MAPPING_FILE));
        String version = sourceId + ":" + definitionHash.substring(0, 12);
        this.mappingVersion = new MappingVersion(sourceId, version, "sha256:" + definitionHash,
                LocalDateTime.now(), "ACTIVE");
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

    private static <T> T read(YAMLMapper mapper, String path, Class<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取本体定义文件：" + path, e);
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
