package com.school.ontology.repository;

import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.SourceRefRow;
import com.school.ontology.core.spi.RelationInstance;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 本体库版投影存储：所有写入落在调用方的本体库单事务内。 */
@Component
public class JdbcProjectionStore implements ProjectionStore {

    private final OntologyRepository repository;

    public JdbcProjectionStore(OntologyRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean markProcessed(String eventId, long revision) {
        return repository.insertProcessedEvent(eventId, revision);
    }

    @Override
    public boolean isProcessed(String eventId) {
        return repository.isProcessed(eventId);
    }

    @Override
    public long appliedRevision(String globalId) {
        return repository.appliedRevision(globalId);
    }

    @Override
    public ProjectionRow object(String globalId) {
        return repository.findObject(globalId);
    }

    @Override
    public void upsertObject(ProjectionRow row) {
        // 条件 UPSERT 在 SQL 层完成版本水位比较；revision 较旧的行不会生效。
        if (row.appliedRevision() < repository.appliedRevision(row.globalId())) {
            return;
        }
        repository.upsertObject(row);
    }

    @Override
    public void upsertRelation(RelationInstance relation) {
        repository.upsertRelation(relation);
    }

    @Override
    public void upsertSourceRef(SourceRefRow ref) {
        repository.upsertSourceRef(ref);
    }

    @Override
    public void appendObjectVersion(String globalId, long revision,
                                    Map<String, Object> snapshot, String eventId) {
        repository.appendObjectVersion(globalId, revision, snapshot, eventId);
    }

    @Override
    public long appendChangeHistory(ChangeRecordRow change) {
        return repository.appendChangeHistory(change);
    }

    @Override
    public void appendImpact(long changeId, String globalId, String role, String basis) {
        repository.appendImpact(changeId, globalId, role, basis);
    }

    @Override
    public void deactivateRelationsFrom(String globalId, LocalDateTime validTo, long revision) {
        deactivateRelationsFromExcept(globalId, Set.of(), validTo, revision);
    }

    @Override
    public void deactivateRelationsFromExcept(String globalId, Set<String> keepRelationIds,
                                              LocalDateTime validTo, long revision) {
        for (RelationInstance relation : repository.relationsFrom(globalId, false)) {
            if (!keepRelationIds.contains(relation.relationId())) {
                repository.deactivateRelation(relation.relationId(), validTo, revision);
            }
        }
    }

    @Override
    public void deactivateRelationsFromExceptTypes(String globalId, Set<String> relationTypes,
                                                   Set<String> keepRelationIds,
                                                   LocalDateTime validTo, long revision) {
        if (relationTypes == null || relationTypes.isEmpty()) {
            // 空类型集绝不退化成「全部类型」：那会把外键关系一并停掉（批次④ 阻断级回归）。
            return;
        }
        for (RelationInstance relation : repository.relationsFrom(globalId, false)) {
            if (!relationTypes.contains(relation.relationType())) {
                continue;
            }
            if (keepRelationIds.contains(relation.relationId())) {
                continue;
            }
            repository.deactivateRelation(relation.relationId(), validTo, revision);
        }
    }

    @Override
    public void deactivateRelationsTo(String globalId, LocalDateTime validTo, long revision) {
        for (RelationInstance relation : repository.relationsTo(globalId, false)) {
            repository.deactivateRelation(relation.relationId(), validTo, revision);
        }
    }

    @Override
    public void tombstoneObject(String globalId, long revision) {
        repository.tombstoneObject(globalId, revision);
    }

    @Override
    public void bumpPlatformRevision() {
        repository.bumpPlatformRevision();
    }

    @Override
    public long platformRevision() {
        return repository.platformRevision();
    }

    @Override
    public void truncateProjection() {
        repository.truncateProjection();
    }

    @Override
    public List<ProjectionRow> objects() {
        return repository.allObjects();
    }

    @Override
    public List<RelationInstance> relations() {
        return repository.allRelations();
    }

    @Override
    public List<RelationInstance> activeRelationsFrom(String globalId) {
        // includeInactive=false：只取当前有效关系，已结束的关系不参与「新增/解除」差集计算。
        return repository.relationsFrom(globalId, false);
    }

    @Override
    public List<RelationInstance> activeRelationsTo(String globalId) {
        return repository.relationsTo(globalId, false);
    }

    @Override
    public long changeHistoryCount() {
        return repository.changeHistoryCount();
    }

    @Override
    public long processedEventCount() {
        return repository.processedEventCount();
    }

    @Override
    public long syncTaskCount() {
        return repository.syncTaskCount();
    }

    @Override
    public long errorLogCount() {
        return repository.errorLogCount();
    }

    @Override
    public long appendSyncTask(String taskType, String status, long snapshotRevision,
                               Map<String, Object> progress) {
        return repository.appendSyncTask(taskType, status, snapshotRevision, progress);
    }

    @Override
    public void updateSyncTask(long taskId, String status, long endRevision,
                               Map<String, Object> progress, String error) {
        repository.updateSyncTask(taskId, status, endRevision, progress, error);
    }

    @Override
    public long appendReconcileRun(String status, List<Map<String, Object>> diffs,
                                   Map<String, Object> summary) {
        return repository.appendReconcileRun(status, diffs, summary);
    }

    @Override
    public void updateReconcileRun(long runId, String status, List<Map<String, Object>> diffs,
                                   Map<String, Object> summary) {
        repository.updateReconcileRun(runId, status, diffs, summary);
    }

    @Override
    public void appendErrorLog(String phase, String refId, String message) {
        repository.appendErrorLog(phase, refId, message);
    }

    @Override
    public void ensureDataSource(String sourceId, String displayName) {
        repository.ensureDataSource(sourceId, displayName);
    }

    @Override
    public void updateDataSource(String sourceId, Map<String, Object> fields) {
        repository.updateDataSource(sourceId, fields);
    }

    @Override
    public Map<String, Object> dataSource(String sourceId) {
        return repository.dataSource(sourceId);
    }
}
