package com.school.ontology.core.projection;

import java.util.Map;

/** 当前投影中的一条对象实例。来源主键与全局 ID 分开存（B3）。 */
public record ProjectionRow(String globalId, String sourceId, String objectType,
                            String sourcePk, String title, Map<String, Object> attributes,
                            String mappingVersion, long appliedRevision, boolean deleted) {

    public ProjectionRow withRevision(long revision) {
        return new ProjectionRow(globalId, sourceId, objectType, sourcePk, title,
                attributes, mappingVersion, revision, deleted);
    }

    public ProjectionRow withDeleted(boolean value) {
        return new ProjectionRow(globalId, sourceId, objectType, sourcePk, title,
                attributes, mappingVersion, appliedRevision, value);
    }
}
