package com.school.ontology.core.sync;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重建当前投影（Q2 / A3 / D3 / 验收五）。
 *
 * <p>只 TRUNCATE 4 张当前投影表：ont_object / ont_relation / ont_source_ref /
 * ont_object_version；审计历史（变更历史、影响、同步任务、消费进度、错误记录、
 * 对账运行）与模型/配置全部保留，platform_revision 只增不减。
 * 重建前后全局 ID、当前属性、关系完全一致，因为稳定 ID 规则与映射版本不变。</p>
 *
 * <p>重建成功即视为一次成功同步：数据源的 snapshot_revision 跟到本次水位、
 * last_success_sync_at 刷新为真实处理时间、状态置 SYNCED、errorCount 清零，
 * 否则会出现「任务表 snap=87 成功，而数据源仍停在 85、时间还是上次导入」的自相矛盾。</p>
 */
@Component
public class RebuildService {

    private final SnapshotSource source;
    private final ProjectionStore store;
    private final ProjectionWriter writer;
    private final ModelRegistry registry;

    public RebuildService(SnapshotSource source, ProjectionStore store, ProjectionWriter writer,
                          ModelRegistry registry) {
        this.source = source;
        this.store = store;
        this.writer = writer;
        this.registry = registry;
    }

    public long rebuild() {
        long watermark = source.watermark();
        long taskId = store.appendSyncTask("REBUILD", "RUNNING", watermark,
                Map.of("scope", "CURRENT_PROJECTION_ONLY"));
        try {
            if (!source.healthy()) {
                throw new IllegalStateException("来源业务库不可用，无法重建当前投影");
            }
            // 1. 只清当前投影；审计历史与去重表保留，重放历史事件仍能去重。
            store.truncateProjection();
            // 2. 与 fullImport 复用同一套「快照 + 稳定 ID + 条件 UPSERT」逻辑。
            LinkedHashMap<String, SnapshotBatch> all = source.snapshot(watermark);
            int objects = 0;
            int relations = 0;
            for (SnapshotBatch batch : all.values()) {
                writer.applySnapshot(batch, watermark);
                objects += batch.objects().size();
                relations += batch.relations().size();
            }
            // 3. 平台版本 bump（只增不减）+ 追加 1 条可追溯运行记录。
            store.bumpPlatformRevision();
            store.updateSyncTask(taskId, "SUCCESS", watermark,
                    Map.of("scope", "CURRENT_PROJECTION_ONLY",
                            "objects", objects, "relations", relations,
                            "truncatedTables", java.util.List.of(
                                    "ont_object", "ont_relation", "ont_source_ref", "ont_object_version"),
                            "preservedTables", java.util.List.of(
                                    "ont_change_history", "ont_change_impact", "ont_sync_task",
                                    "ont_processed_event", "ont_error_log", "ont_reconcile_run",
                                    "ont_model_def", "ont_mapping_version", "ont_data_source",
                                    "ont_platform_revision")), null);
            // 4. 数据源水位与新鲜度跟随重建结果，避免「任务成功但数据源仍是旧水位」。
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("snapshotRevision", watermark);
            update.put("lastSuccessSyncAt", LocalDateTime.now());
            update.put("status", "SYNCED");
            update.put("errorCount", 0L);
            store.updateDataSource(registry.sourceId(), update);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.updateSyncTask(taskId, "FAILED", watermark, Map.of(), message);
            store.appendErrorLog("REBUILD", String.valueOf(taskId), message);
            // 重建是「先清空当前投影、再逐批写回」的非事务流程，中途失败时投影一定残缺。
            // 数据源必须立刻从 SYNCED 降级，否则 overview 会继续对外宣称「已同步」，
            // 而实际对象数已经少了一大半——这种静默错误比直接报错危险得多（P7-2）。
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("status", "DEGRADED");
            store.updateDataSource(registry.sourceId(), degraded);
        }
        return taskId;
    }
}
