package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 字段映射定义：简单直连走 source，复杂转换通过 transform key 查 FieldMapping 策略表。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldMappingDef {

    public String target;
    public String source;
    public String transform;
    public LookupDef lookup;

    /** 联表解引用参数；标识符来自可信 YAML，适配层仍会做白名单校验。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LookupDef {
        public String table;
        public String nameColumn;
        public String joinColumn;
        public String nameTable;
    }
}
