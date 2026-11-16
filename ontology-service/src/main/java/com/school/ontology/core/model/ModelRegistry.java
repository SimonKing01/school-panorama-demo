package com.school.ontology.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型注册中心：所有分派都是 {@code map.get(key)}，禁止按业务类型写 switch/if。
 * 由 ModelDefinitionLoader 校验通过后构造，运行期只读。
 */
public class ModelRegistry {

    /**
     * 派生关系的生成规则 kind：关系实例由联表规则「算出来」，不是外键直连。
     *
     * <p>这是 {@link com.school.ontology.core.spi.RelationRule#relationType()} 的返回常量，
     * 仅作为策略表键使用；判断某关系类型是不是派生关系一律走
     * {@link #isDerivedRelation(String)}，代码里不出现任何业务关系类型标识。</p>
     */
    public static final String DERIVED_GENERATION_KIND = "JOIN_RULE";

    private final Map<String, ObjectTypeDef> objectTypes;
    private final Map<String, RelationTypeDef> relationTypes;
    private final Map<String, ObjectMappingDef> objectMappings;
    private final String sourceId;
    private final List<String> orderedTypeIds;

    public ModelRegistry(Map<String, ObjectTypeDef> objectTypes,
                         Map<String, RelationTypeDef> relationTypes,
                         Map<String, ObjectMappingDef> objectMappings,
                         String sourceId) {
        this.objectTypes = Collections.unmodifiableMap(new LinkedHashMap<>(objectTypes));
        this.relationTypes = Collections.unmodifiableMap(new LinkedHashMap<>(relationTypes));
        this.objectMappings = Collections.unmodifiableMap(new LinkedHashMap<>(objectMappings));
        this.sourceId = sourceId;
        this.orderedTypeIds = List.copyOf(objectTypes.keySet());
    }

    public ObjectTypeDef typeOf(String objectType) {
        ObjectTypeDef def = objectTypes.get(objectType);
        if (def == null) {
            throw new IllegalArgumentException("未注册的对象类型：" + objectType);
        }
        return def;
    }

    public RelationTypeDef relationOf(String relationType) {
        RelationTypeDef def = relationTypes.get(relationType);
        if (def == null) {
            throw new IllegalArgumentException("未注册的关系类型：" + relationType);
        }
        return def;
    }

    public ObjectMappingDef mappingOf(String objectType) {
        ObjectMappingDef def = objectMappings.get(objectType);
        if (def == null) {
            throw new IllegalArgumentException("未注册的对象映射：" + objectType);
        }
        return def;
    }

    /** 通过来源表名反查对象类型，供事件解析与重建使用（数据驱动，无类型分支）。 */
    public String typeBySourceTable(String sourceTable) {
        for (Map.Entry<String, ObjectMappingDef> entry : objectMappings.entrySet()) {
            if (sourceTable.equals(entry.getValue().sourceTable)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("没有对象映射使用来源表：" + sourceTable);
    }

    /** 是否存在某个对象映射使用指定来源表（避免调用方捕获异常做控制流）。 */
    public boolean hasTypeByTable(String sourceTable) {
        for (ObjectMappingDef mapping : objectMappings.values()) {
            if (sourceTable != null && sourceTable.equals(mapping.sourceTable)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasType(String objectType) {
        return objectTypes.containsKey(objectType);
    }

    public boolean hasRelation(String relationType) {
        return relationTypes.containsKey(relationType);
    }

    /**
     * 该关系类型是否为「派生关系」：存在定义且 {@code generation.kind == JOIN_RULE}。
     *
     * <p>派生关系的成立与否取决于「联表算出来的另一端还在不在」，因此事件只需要重扫
     * 起点对象就能重算完整集合；外键关系则只由起点对象自己的字段决定，绝不该被邻居刷新碰。
     * 这个区分必须由定义决定，不能写死关系类型 id（定义真正驱动运行时）。</p>
     */
    public boolean isDerivedRelation(String relationType) {
        RelationTypeDef def = relationType == null ? null : relationTypes.get(relationType);
        return def != null && def.generation != null
                && DERIVED_GENERATION_KIND.equals(def.generation.kind);
    }

    /**
     * 该对象类型是否声明了派生关系规则（即它的出边里有没有 JOIN_RULE 类型的边）。
     *
     * <p>相关对象的刷新批次是否需要顺带重算派生关系，完全由映射定义决定：
     * 没有派生规则的类型（如教室）只刷新属性，不必付出「按类型停边」的额外查询代价。</p>
     */
    public boolean hasDerivedRelationRules(String objectType) {
        ObjectMappingDef mapping = objectType == null ? null : objectMappings.get(objectType);
        if (mapping == null || mapping.joinRelationRules == null) {
            return false;
        }
        for (ObjectMappingDef.JoinRelationRule rule : mapping.joinRelationRules) {
            if (rule != null && isDerivedRelation(rule.relationType)) {
                return true;
            }
        }
        return false;
    }

    /** 全部派生关系类型（运行时从定义读出，新增派生关系类型时调用方零改动）。 */
    public Set<String> derivedRelationTypes() {
        Set<String> result = new LinkedHashSet<>();
        for (RelationTypeDef def : relationTypes.values()) {
            if (def != null && isDerivedRelation(def.id)) {
                result.add(def.id);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public Collection<ObjectTypeDef> types() {
        return objectTypes.values();
    }

    public Collection<RelationTypeDef> relations() {
        return relationTypes.values();
    }

    public Collection<ObjectMappingDef> mappings() {
        return objectMappings.values();
    }

    public List<String> orderedTypeIds() {
        return orderedTypeIds;
    }

    public String sourceId() {
        return sourceId;
    }

    /** 某类型所有可搜索字段（全局直达搜索按定义命中，不硬编码姓名/编号）。 */
    public List<String> searchableFields(String objectType) {
        ObjectTypeDef def = objectTypes.get(objectType);
        return def == null ? new ArrayList<>() : new ArrayList<>(def.searchableFields);
    }
}
