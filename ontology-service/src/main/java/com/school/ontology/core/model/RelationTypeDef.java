package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 关系类型定义（B2）：含起止类型、正反展示名、方向、基数、业务有效时间与生成规则。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelationTypeDef {

    public String id;
    public String displayName;
    public String fromType;
    public String toType;
    public String forwardName;
    public String reverseName;
    public String direction;
    public String cardinality;
    public boolean businessEffectiveTime;
    public GenerationRule generation;
    public String sourceMappingRef;
}
