package com.school.ontology.core.sync;

import com.school.ontology.common.Resp;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.repository.OntologyRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全量导入：快照水位 W0 + 增量衔接（架构 §4.4，D1/D2/验收三-4）。
 *
 * <p>阶段 0 取 W0 → 阶段 1 按定义分页快照条件 UPSERT（applied_revision=W0）
 * → 阶段 2 无论水位是否变化都执行一次 catchUp(W0) 兜底重放
 * → 阶段 3 更新最后成功同步时间、快照水位与平台版本。
 * 重复导入因稳定 ID + 去重 + 条件 UPSERT 不产生重复对象与关系。</p>
 */
@Component
public class SyncTaskService {

    private final SnapshotSource source;
    private final ProjectionStore store;
    private final ProjectionWriter writer;
    private final ModelRegistry registry;
    private final CatchUpService catchUp;
    private final OntologyRepository repository;

    public SyncTaskService(SnapshotSource source, ProjectionStore store, ProjectionWriter writer,
                           ModelRegistry registry, CatchUpService catchUp,
                           OntologyRepository repository) {
        this.source = source;
        this.store = store;
        this.writer = writer;
        this.registry = registry;
        this.catchUp = catchUp;
        this.repository = repository;
    }

    public long fullImport() {
        return importProjection(false, -1L);
    }

    /** 失败重试：从同一水位幂等重跑，不会产生重复对象与关系。 */
    public long retry(long failedTaskId) {
        return importProjection(true, failedTaskId);
    }

    private long importProjection(boolean isRetry, long failedTaskId) {
        long w0 = source.watermark();
        Map<String, Object> progress = progressMap(0, 0, "", 0);
        long taskId = store.appendSyncTask("FULL_IMPORT", "RUNNING", w0, progress);
        try {
            if (!source.healthy()) {
                throw new IllegalStateException("来源业务库不可用，无法执行全量导入");
            }
            LinkedHashMap<String, SnapshotBatch> all = source.snapshot(w0);
            int total = 0;
            for (SnapshotBatch batch : all.values()) {
                total += batch.objects().size();
            }
            int applied = 0;
            for (Map.Entry<String, SnapshotBatch> entry : all.entrySet()) {
                writer.applySnapshot(entry.getValue(), w0);
                applied += entry.getValue().objects().size();
                store.updateSyncTask(taskId, "RUNNING", 0,
                        progressMap(total, applied, entry.getKey(),
                                total == 0 ? 100 : applied * 100 / total), null);
            }
            long w1 = source.watermark();
            // 阶段 2：导入期间的业务修改不遗漏、不被旧快照覆盖。
            int caught = catchUp.catchUp(w0);
            Map<String, Object> finalProgress = progressMap(total, applied, "DONE", 100);
            finalProgress.put("endRevision", w1);
            finalProgress.put("caughtUpEvents", caught);
            finalProgress.put("retry", isRetry);
            finalProgress.put("retriedFromTaskId", failedTaskId);
            store.updateSyncTask(taskId, "SUCCESS", w1, finalProgress, null);

            Map<String, Object> update = new LinkedHashMap<>();
            update.put("lastSuccessSyncAt", LocalDateTime.now());
            update.put("snapshotRevision", w1);
            update.put("currentSourceRevision", w1);
            update.put("pendingCount", 0L);
            update.put("status", "SYNCED");
            store.updateDataSource(registry.sourceId(), update);
            store.bumpPlatformRevision();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.updateSyncTask(taskId, "FAILED", w0, progress, message);
            store.appendErrorLog("FULL_IMPORT", String.valueOf(taskId), message);
        }
        return taskId;
    }

    public Map<String, Object> progress(long taskId) {
        Map<String, Object> row = repository.syncTask(taskId);
        if (row.isEmpty()) {
            return Resp.map("taskId", taskId, "status", "NOT_FOUND", "retryable", false);
        }
        return Resp.map(
                "taskId", OntologyRepository.lng(row.get("task_id")),
                "type", OntologyRepository.str(row.get("task_type")),
                "status", OntologyRepository.str(row.get("status")),
                "snapshotRevision", OntologyRepository.lng(row.get("snapshot_revision")),
                "endRevision", OntologyRepository.lng(row.get("end_revision")),
                "progress", repository.parseMap(row.get("progress_json")),
                "startedAt", Resp.ts(OntologyRepository.dateTime(row.get("started_at"))),
                "finishedAt", Resp.ts(OntologyRepository.dateTime(row.get("finished_at"))),
                "error", OntologyRepository.str(row.get("error")),
                "retryable", "FAILED".equals(OntologyRepository.str(row.get("status"))));
    }

    public Map<String, Object> tasks(String type, int page, int size) {
        int offset = Math.max(page - 1, 0) * size;
        List<Map<String, Object>> all = repository.syncTasks(size * 4, 0);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : all) {
            if (type == null || type.isBlank()
                    || type.equals(OntologyRepository.str(row.get("task_type")))) {
                filtered.add(Resp.map(
                        "taskId", OntologyRepository.lng(row.get("task_id")),
                        "type", OntologyRepository.str(row.get("task_type")),
                        "status", OntologyRepository.str(row.get("status")),
                        "snapshotRevision", OntologyRepository.lng(row.get("snapshot_revision")),
                        "endRevision", OntologyRepository.lng(row.get("end_revision")),
                        "startedAt", Resp.ts(OntologyRepository.dateTime(row.get("started_at"))),
                        "finishedAt", Resp.ts(OntologyRepository.dateTime(row.get("finished_at"))),
                        "operator", OntologyRepository.str(row.get("operator"))));
            }
        }
        int total = filtered.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + size, total);
        return Resp.map("total", total, "page", page, "size", size,
                "items", filtered.subList(from, to));
    }

    private static Map<String, Object> progressMap(int total, int applied,
                                                   String currentType, int percent) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("total", total);
        progress.put("scanned", applied);
        progress.put("applied", applied);
        progress.put("percent", percent);
        progress.put("currentType", currentType);
        return progress;
    }
}
