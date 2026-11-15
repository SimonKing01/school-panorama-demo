package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 属性定义。{@code from} 非空表示来源直接存在（外键解引用），
 * 是 C2「三类信息可辨识」中的第一类。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttributeDef {

    public String name;
    public String type;
    public String displayName;
    public boolean required;
    public boolean unique;
    public String validate;
    public String from;

    // 以下字段不是 YAML 字段：由 SnapshotBuilder 从 school-source.yaml 的
    // FieldMappingDef.lookup 注入，使 FieldMapping.map(rec, def, ctx) 保持通用签名。
    public String sourceColumn;
    public String lookupTable;
    public String lookupNameColumn;
    public String lookupJoinColumn;
    public String lookupNameTable;
}
