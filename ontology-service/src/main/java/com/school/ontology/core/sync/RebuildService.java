package com.school.ontology.core.sync;

import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重建当前投影（Q2 / A3 / D3 / 验收五）。
 *
 * <p>只 TRUNCATE 4 张当前投影表：ont_object / ont_relation / ont_source_ref /
 * ont_object_version；审计历史（变更历史、影响、同步任务、消费进度、错误记录、
 * 对账运行）与模型/配置全部保留，platform_revision 只增不减。
 * 重建前后全局 ID、当前属性、关系完全一致，因为稳定 ID 规则与映射版本不变。</p>
 */
@Component
public class RebuildService {

    private final SnapshotSource source;
    private final ProjectionStore store;
    private final ProjectionWriter writer;

    public RebuildService(SnapshotSource source, ProjectionStore store, ProjectionWriter writer) {
        this.source = source;
        this.store = store;
        this.writer = writer;
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
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.updateSyncTask(taskId, "FAILED", watermark, Map.of(), message);
            store.appendErrorLog("REBUILD", String.valueOf(taskId), message);
        }
        return taskId;
    }
}
