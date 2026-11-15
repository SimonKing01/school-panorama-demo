package com.school.ontology.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型注册中心：所有分派都是 {@code map.get(key)}，禁止按业务类型写 switch/if。
 * 由 ModelDefinitionLoader 校验通过后构造，运行期只读。
 */
public class ModelRegistry {

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
