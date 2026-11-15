package com.school.ontology.core.spi;

import com.school.ontology.core.model.RelationTypeDef;

import java.util.List;

/** 外键 / 联表 / 规则生成关系。内核按 relationType 从策略表取实现，无类型分支。 */
public interface RelationRule {

    /**
     * 通用实现返回生成规则 kind（FOREIGN_KEY / JOIN_RULE），
     * 由 SpiRegistry 按模型定义把每个关系类型 id 映射到对应实现。
     */
    String relationType();

    List<RelationInstance> derive(SourceRecord rec, RelationTypeDef def, MappingContext ctx);
}
