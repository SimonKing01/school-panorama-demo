package com.school.ontology.support;

import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.SourceRefRow;
import com.school.ontology.core.spi.RelationInstance;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试用内存投影存储：与 JDBC 实现共享同一套幂等语义，
 * 使「重复事件 / 乱序 / 重建保留历史」可以在不启动数据库的条件下被真实验证。
 *
 * <p>truncateProjection 只清 4 类当前投影数据，审计历史集合一律保留。</p>
 */
public class InMemoryProjectionStore implements ProjectionStore {

    private final Map<String, ProjectionRow> objects = new LinkedHashMap<>();
    private final Map<String, RelationInstance> relations = new LinkedHashMap<>();
    private final Map<String, SourceRefRow> sourceRefs = new LinkedHashMap<>();
    private final List<Map<String, Object>> objectVersions = new ArrayList<>();
    private final Set<String> processedEvents = new LinkedHashSet<>();
    private final List<ChangeRecordRow> changeHistory = new ArrayList<>();
    private final Map<Long, List<Map<String, Object>>> impacts = new LinkedHashMap<>();
    private final List<Map<String, Object>> syncTasks = new ArrayList<>();
    private final List<Map<String, Object>> reconcileRuns = new ArrayList<>();
    private final List<Map<String, Object>> errorLogs = new ArrayList<>();
    private final Map<String, Map<String, Object>> dataSources = new LinkedHashMap<>();
    private long platformRevision = 0L;
    private long changeSequence = 0L;
    private long taskSequence = 0L;
    private long runSequence = 0L;

    @Override
    public boolean markProcessed(String eventId, long revision) {
        return processedEvents.add(eventId);
    }

    @Override
    public boolean isProcessed(String eventId) {
        return processedEvents.contains(eventId);
    }

    @Override
    public long appliedRevision(String globalId) {
        ProjectionRow row = objects.get(globalId);
        return row == null ? 0L : row.appliedRevision();
    }

    @Override
    public ProjectionRow object(String globalId) {
        return objects.get(globalId);
    }

    @Override
    public void upsertObject(ProjectionRow row) {
        ProjectionRow current = objects.get(row.globalId());
        if (current != null && row.appliedRevision() < current.appliedRevision()) {
            return;
        }
        objects.put(row.globalId(), row);
    }

    @Override
    public void upsertRelation(RelationInstance relation) {
        RelationInstance current = relations.get(relation.relationId());
        if (current != null && relation.appliedRevision() < current.appliedRevision()) {
            return;
        }
        relations.put(relation.relationId(), relation);
    }

    @Override
    public void upsertSourceRef(SourceRefRow ref) {
        sourceRefs.put(ref.globalId() + ":" + ref.sourceId(), ref);
    }

    @Override
    public void appendObjectVersion(String globalId, long revision,
                                    Map<String, Object> snapshot, String eventId) {
        objectVersions.add(Map.of("globalId", globalId, "revision", revision,
                "eventId", eventId == null ? "" : eventId));
    }

    @Override
    public long appendChangeHistory(ChangeRecordRow change) {
        changeHistory.add(change);
        return ++changeSequence;
    }

    @Override
    public void appendImpact(long changeId, String globalId, String role, String basis) {
        impacts.computeIfAbsent(changeId, ignored -> new ArrayList<>())
                .add(Map.of("globalId", globalId, "role", role, "basis", basis));
    }

    @Override
    public void deactivateRelationsFrom(String globalId, LocalDateTime validTo, long revision) {
        deactivateRelationsFromExcept(globalId, Set.of(), validTo, revision);
    }

    @Override
    public void deactivateRelationsFromExcept(String globalId, Set<String> keepRelationIds,
                                              LocalDateTime validTo, long revision) {
        for (Map.Entry<String, RelationInstance> entry : new ArrayList<>(relations.entrySet())) {
            RelationInstance relation = entry.getValue();
            if (relation.active() && relation.fromGlobalId().equals(globalId)
                    && !keepRelationIds.contains(relation.relationId())
                    && revision >= relation.appliedRevision()) {
                relations.put(entry.getKey(), deactivated(relation, validTo, revision));
            }
        }
    }

    @Override
    public void deactivateRelationsTo(String globalId, LocalDateTime validTo, long revision) {
        for (Map.Entry<String, RelationInstance> entry : new ArrayList<>(relations.entrySet())) {
            RelationInstance relation = entry.getValue();
            if (relation.active() && relation.toGlobalId().equals(globalId)
                    && revision >= relation.appliedRevision()) {
                relations.put(entry.getKey(), deactivated(relation, validTo, revision));
            }
        }
    }

    @Override
    public void tombstoneObject(String globalId, long revision) {
        ProjectionRow row = objects.get(globalId);
        if (row != null && revision >= row.appliedRevision()) {
            objects.put(globalId, row.withDeleted(true).withRevision(revision));
        }
    }

    @Override
    public void bumpPlatformRevision() {
        platformRevision++;
    }

    @Override
    public long platformRevision() {
        return platformRevision;
    }

    @Override
    public void truncateProjection() {
        objects.clear();
        relations.clear();
        sourceRefs.clear();
        objectVersions.clear();
    }

    @Override
    public List<ProjectionRow> objects() {
        return new ArrayList<>(objects.values());
    }

    @Override
    public List<RelationInstance> relations() {
        return new ArrayList<>(relations.values());
    }

    @Override
    public List<RelationInstance> activeRelationsFrom(String globalId) {
        return activeRelations(globalId, true);
    }

    @Override
    public List<RelationInstance> activeRelationsTo(String globalId) {
        return activeRelations(globalId, false);
    }

    /** 与 JDBC 实现对齐：只返回 active 且指定端匹配的关系。 */
    private List<RelationInstance> activeRelations(String globalId, boolean asFrom) {
        List<RelationInstance> result = new ArrayList<>();
        for (RelationInstance relation : relations.values()) {
            if (!relation.active()) {
                continue;
            }
            String endpoint = asFrom ? relation.fromGlobalId() : relation.toGlobalId();
            if (endpoint != null && endpoint.equals(globalId)) {
                result.add(relation);
            }
        }
        return result;
    }

    @Override
    public long changeHistoryCount() {
        return changeHistory.size();
    }

    @Override
    public long processedEventCount() {
        return processedEvents.size();
    }

    @Override
    public long syncTaskCount() {
        return syncTasks.size();
    }

    @Override
    public long errorLogCount() {
        return errorLogs.size();
    }

    @Override
    public long appendSyncTask(String taskType, String status, long snapshotRevision,
                               Map<String, Object> progress) {
        long id = ++taskSequence;
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", id);
        task.put("taskType", taskType);
        task.put("status", status);
        task.put("snapshotRevision", snapshotRevision);
        task.put("progress", progress);
        syncTasks.add(task);
        return id;
    }

    @Override
    public void updateSyncTask(long taskId, String status, long endRevision,
                               Map<String, Object> progress, String error) {
        for (Map<String, Object> task : syncTasks) {
            if (((Number) task.get("taskId")).longValue() == taskId) {
                task.put("status", status);
                task.put("endRevision", endRevision);
                task.put("progress", progress);
                task.put("error", error);
                return;
            }
        }
    }

    @Override
    public long appendReconcileRun(String status, List<Map<String, Object>> diffs,
                                   Map<String, Object> summary) {
        long id = ++runSequence;
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", id);
        run.put("status", status);
        run.put("diffs", diffs);
        run.put("summary", summary);
        reconcileRuns.add(run);
        return id;
    }

    @Override
    public void updateReconcileRun(long runId, String status, List<Map<String, Object>> diffs,
                                   Map<String, Object> summary) {
        for (Map<String, Object> run : reconcileRuns) {
            if (((Number) run.get("runId")).longValue() == runId) {
                run.put("status", status);
                run.put("diffs", diffs);
                run.put("summary", summary);
                return;
            }
        }
    }

    @Override
    public void appendErrorLog(String phase, String refId, String message) {
        errorLogs.add(Map.of("phase", phase, "refId", refId == null ? "" : refId,
                "message", message == null ? "" : message));
    }

    @Override
    public void ensureDataSource(String sourceId, String displayName) {
        dataSources.computeIfAbsent(sourceId, ignored -> new LinkedHashMap<>());
    }

    @Override
    public void updateDataSource(String sourceId, Map<String, Object> fields) {
        dataSources.computeIfAbsent(sourceId, ignored -> new LinkedHashMap<>()).putAll(fields);
    }

    @Override
    public Map<String, Object> dataSource(String sourceId) {
        return new LinkedHashMap<>(dataSources.getOrDefault(sourceId, Map.of()));
    }

    public List<ChangeRecordRow> changeHistory() {
        return changeHistory;
    }

    public ChangeRecordRow lastChange() {
        return changeHistory.isEmpty() ? null : changeHistory.get(changeHistory.size() - 1);
    }

    public List<Map<String, Object>> syncTasks() {
        return syncTasks;
    }

    /** 对象版本行（测试用：验证「无变化不追加版本」与邻居刷新次数）。 */
    public List<Map<String, Object>> objectVersions() {
        return new ArrayList<>(objectVersions);
    }

    /** 某条变更记录的影响对象行（测试用：验证角色划分与「不给同批兄弟互标」）。 */
    public List<Map<String, Object>> impactsOf(long changeId) {
        return new ArrayList<>(impacts.getOrDefault(changeId, List.of()));
    }

    private static RelationInstance deactivated(RelationInstance relation,
                                                LocalDateTime validTo, long revision) {
        return new RelationInstance(relation.relationId(), relation.relationType(),
                relation.fromGlobalId(), relation.toGlobalId(), relation.reason(),
                relation.sourceTable(), relation.sourcePk(), relation.sourceHash(),
                relation.mappingVersion(), relation.validFrom(), validTo, false, revision);
    }
}
