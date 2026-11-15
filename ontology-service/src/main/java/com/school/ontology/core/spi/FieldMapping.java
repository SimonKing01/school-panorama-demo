package com.school.ontology.core.spi;

import com.school.ontology.core.model.AttributeDef;

/** 字段映射 / 简单转换。复杂转换才写 Java，简单直连由 YAML 定义完成。 */
public interface FieldMapping {

    /** 对应 school-source.yaml 的 transform key，如 LOOKUP_NAME / DATE_yyyyMMdd。 */
    String key();

    Object map(SourceRecord rec, AttributeDef def, MappingContext ctx);
}
