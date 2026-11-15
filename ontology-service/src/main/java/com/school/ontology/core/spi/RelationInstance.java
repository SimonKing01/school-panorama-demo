package com.school.ontology.core.spi;

import java.time.LocalDateTime;

/**
 * 关系实例：两端对象 + 存在原因 + 来源原始记录 + 映射版本 + 业务有效时间。
 * validTo/active 让「旧关系结束、新关系建立」在关系图与变化历史中可见。
 */
public record RelationInstance(String relationId, String relationType,
                               String fromGlobalId, String toGlobalId,
                               String reason, String sourceTable, String sourcePk,
                               String sourceHash, String mappingVersion,
                               LocalDateTime validFrom, LocalDateTime validTo,
                               boolean active, long appliedRevision) {

    public RelationInstance withRevision(long revision) {
        return new RelationInstance(relationId, relationType, fromGlobalId, toGlobalId, reason,
                sourceTable, sourcePk, sourceHash, mappingVersion, validFrom, validTo,
                active, revision);
    }
}
