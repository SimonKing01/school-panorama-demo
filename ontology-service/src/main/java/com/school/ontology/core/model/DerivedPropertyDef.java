package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 派生属性定义（C2 第二类）：{@code provider} 为 Java 计算，
 * {@code resolver} 为依赖查询时间的动态计算，二者都通过策略表按 key 分派。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DerivedPropertyDef {

    public String name;
    public String type;
    public String displayName;
    public String provider;
    public String resolver;
}
