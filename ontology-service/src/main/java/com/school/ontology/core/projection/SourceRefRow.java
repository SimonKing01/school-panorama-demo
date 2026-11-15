package com.school.ontology.core.projection;

/** 来源引用：全局 ID ↔ 来源表/主键/编号/指纹/映射版本，用于来源追溯（验收五）。 */
public record SourceRefRow(String globalId, String sourceId, String sourceTable,
                           String sourcePk, String sourceCode, String sourceHash,
                           String mappingVersion, long lastSeenRevision) {
}
