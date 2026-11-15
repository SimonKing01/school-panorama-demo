package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源映射定义（B4）：一张来源表 → 一种对象。
 * 字段映射、稳定 ID、外键关系、联表关系、派生属性全部是数据，内核按定义执行。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectMappingDef {

    /** 对象类型标识，由加载器从 objectMappings 的 map key 填入。 */
    public String objectType;
    public String sourceTable;
    public String pk;
    public List<FieldMappingDef> fieldMappings = new ArrayList<>();
    public StableIdRule stableId;
    public List<RelationRuleDef> relationRules = new ArrayList<>();
    public List<JoinRelationRule> joinRelationRules = new ArrayList<>();
    public List<DerivedPropertyBinding> derivedProperties = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StableIdRule {
        public String rule;
    }

    /** 外键关系规则；validFromColumns 让「业务有效时间」也由定义驱动。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelationRuleDef {
        public String relationType;
        public String fk;
        public String targetObjectType;
        public List<String> validFromColumns = new ArrayList<>();
        public int enabledAfterBatch = 1;
    }

    /** 联表派生关系：全部字段化，避免内核解析领域字符串规则。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JoinRelationRule {
        public String relationType;
        public String rule;
        public boolean distinct;
        public String sourceColumn;
        public String joinTable;
        public String joinFilterColumn;
        public String joinSelectColumn;
        public String targetObjectType;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DerivedPropertyBinding {
        public String name;
        public String provider;
        public String resolver;
        public List<String> inputs = new ArrayList<>();
    }
}
