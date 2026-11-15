package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三份 YAML 定义的根文档类型。放在同一个文件中作为静态嵌套类，
 * 使「定义文档」与「实例定义」层次清晰，同时避免大量同名小文件。
 */
public final class ModelDocument {

    private ModelDocument() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ObjectTypeDocument {
        public int version;
        public List<ObjectTypeDef> objectTypes = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelationTypeDocument {
        public int version;
        public List<RelationTypeDef> relationTypes = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SourceMappingDocument {
        public int version;
        public DataSourceDef dataSource;
        public Map<String, ObjectMappingDef> objectMappings = new LinkedHashMap<>();
        public List<SpiBinding> providers = new ArrayList<>();
        public List<SpiBinding> resolvers = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataSourceDef {
        public String id;
        public String displayName;
        public String type;
        public String jdbcRef;
        public WatermarkDef watermark;
        public String healthCheckSql;
        public String businessTimeSql;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WatermarkDef {
        public String kind;
        public String table;
        public String column;
    }

    /** Provider / Resolver 的 key → 实现类全限定名，加载时强校验类存在与接口兼容。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpiBinding {
        public String key;
        public String impl;
    }
}
