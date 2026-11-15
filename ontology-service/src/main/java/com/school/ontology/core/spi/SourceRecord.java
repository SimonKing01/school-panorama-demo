package com.school.ontology.core.spi;

import java.util.Map;

/** 来源表一行记录：列名 → 值（已归一化）、来源主键、记录指纹。 */
public record SourceRecord(String table, String pk, Map<String, Object> columns, String recordHash) {

    public Object column(String name) {
        return columns.get(name);
    }

    public String text(String name) {
        Object value = columns.get(name);
        return value == null ? null : String.valueOf(value);
    }
}
