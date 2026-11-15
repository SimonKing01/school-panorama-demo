package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 关系生成规则。{@code kind} 只作为策略表键（FOREIGN_KEY / JOIN_RULE），
 * 内核据 kind 从 SpiRegistry 取实现，不写业务类型分支。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerationRule {

    public String kind;
    public String sourceTable;
    public String fkColumn;
    public String targetTable;
    public String rule;
    public boolean distinct;

    /** 联表派生关系（JOIN_RULE）的来源列与联表参数，全部由定义驱动。 */
    public String sourceColumn;
    public String joinTable;
    public String joinFilterColumn;
    public String joinSelectColumn;

    /** 业务有效时间的来源列（如 lesson_date + start_time），内核不认识课表概念。 */
    public java.util.List<String> validFromColumns = new java.util.ArrayList<>();
}
