package com.school.ontology.core.spi;

import com.school.ontology.core.model.DerivedPropertyDef;

/** 派生属性（联表/聚合计算）。学校领域实现集中在 adapter.school.provider。 */
public interface DerivedPropertyProvider {

    /** 对应 object-types.yaml / school-source.yaml 的 provider key。 */
    String key();

    Object compute(SourceRecord rec, DerivedPropertyDef def, MappingContext ctx);
}
