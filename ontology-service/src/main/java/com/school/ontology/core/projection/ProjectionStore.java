package com.school.ontology.core.projection;

import com.school.ontology.core.spi.RelationInstance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投影存储抽象。生产实现用本体库 JDBC（条件 UPSERT + 单事务），
 * 单元测试用内存实现；两者共享同一套幂等语义，便于证明去重/乱序/重建。
 *
 * <p>「重建只 TRUNCATE 当前投影」由 {@code truncateProjection} 保证：
 * 实现只清 ont_object/ont_relation/ont_source_ref/ont_object_version 对应的集合，
 * 审计历史集合一律不动。</p>
 */
public interface ProjectionStore {

    /** 幂等去重：首次插入返回 true，已存在返回 false。 */
    boolean markProcessed(String eventId, long revision);

    boolean isProcessed(String eventId);

    long appliedRevision(String globalId);

    ProjectionRow object(String globalId);

    /** 条件 UPSERT：仅当 incoming.appliedRevision >= 当前 appliedRevision 才生效。 */
    void upsertObject(ProjectionRow row);

    void upsertRelation(RelationInstance relation);

    void upsertSourceRef(SourceRefRow ref);

    void appendObjectVersion(String globalId, long revision, Map<String, Object> snapshot, String eventId);

    long appendChangeHistory(ChangeRecordRow change);

    void appendImpact(long changeId, String globalId, String role, String basis);

    void deactivateRelationsFrom(String globalId, LocalDateTime validTo, long revision);

    void deactivateRelationsFromExcept(String globalId, Set<String> keepRelationIds,
                                       LocalDateTime validTo, long revision);

    void deactivateRelationsTo(String globalId, LocalDateTime validTo, long revision);

    void tombstoneObject(String globalId, long revision);

    void bumpPlatformRevision();

    long platformRevision();

    void truncateProjection();

    List<ProjectionRow> objects();

    List<RelationInstance> relations();

    long changeHistoryCount();

    long processedEventCount();

    long syncTaskCount();

    long errorLogCount();

    long appendSyncTask(String taskType, String status, long snapshotRevision,
                        Map<String, Object> progress);

    void updateSyncTask(long taskId, String status, long endRevision,
                        Map<String, Object> progress, String error);

    long appendReconcileRun(String status, List<Map<String, Object>> diffs,
                            Map<String, Object> summary);

    void updateReconcileRun(long runId, String status, List<Map<String, Object>> diffs,
                            Map<String, Object> summary);

    void appendErrorLog(String phase, String refId, String message);

    void ensureDataSource(String sourceId, String displayName);

    void updateDataSource(String sourceId, Map<String, Object> fields);

    Map<String, Object> dataSource(String sourceId);
}
