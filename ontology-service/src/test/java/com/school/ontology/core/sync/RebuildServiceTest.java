package com.school.ontology.core.sync;

import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.support.FakeSnapshotSource;
import com.school.ontology.support.InMemoryProjectionStore;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A3 / D3 / 验收五：重建后身份、属性、关系不变，审计历史条数不减少。 */
class RebuildServiceTest {

    @Test
    void 重建只清当前投影且历史不减平台版本只增() {
        InMemoryProjectionStore store = new InMemoryProjectionStore();
        ProjectionWriter writer = new ProjectionWriter(store, TestFixtures.minimalRegistry());
        String gid = TestFixtures.gid("student", "1");
        ProjectionRow row = TestFixtures.object("student", "1", 84,
                Map.of("name", "陈一诺", "className", "初一（1）班"));
        RelationInstance relation = TestFixtures.relation("student_in_class", gid,
                TestFixtures.gid("class", "1"), 84);
        SnapshotBatch batch = TestFixtures.batch(row, relation);
        writer.applySnapshot(batch, 84);

        // 审计历史：变更 2 条、消费进度 1 条、同步任务 1 条、错误记录 1 条。
        store.appendChangeHistory(new ChangeRecordRow("evt-1", gid, "ATTR_CHANGE", "EVENT",
                Map.of(), Map.of(), List.of(), List.of(), List.of(), "演示", "演示管理员",
                LocalDateTime.of(2026, 11, 14, 10, 15), TestFixtures.MAPPING_VERSION));
        store.appendChangeHistory(new ChangeRecordRow("evt-2", gid, "REL_CHANGE", "EVENT",
                Map.of(), Map.of(), List.of(), List.of(), List.of(), "演示", "演示管理员",
                LocalDateTime.of(2026, 11, 14, 10, 20), TestFixtures.MAPPING_VERSION));
        store.markProcessed("evt-1", 84);
        store.appendSyncTask("FULL_IMPORT", "SUCCESS", 84, Map.of());
        store.appendErrorLog("TEST", "evt-1", "虚拟历史错误");
        store.bumpPlatformRevision();
        store.bumpPlatformRevision();

        long historyBefore = store.changeHistoryCount();
        long processedBefore = store.processedEventCount();
        long tasksBefore = store.syncTaskCount();
        long errorsBefore = store.errorLogCount();
        long revisionBefore = store.platformRevision();
        String globalIdBefore = store.objects().get(0).globalId();
        Map<String, Object> attributesBefore = new LinkedHashMap<>(store.objects().get(0).attributes());
        String relationIdBefore = store.relations().get(0).relationId();

        LinkedHashMap<String, SnapshotBatch> batches = new LinkedHashMap<>();
        batches.put("student", batch);
        RebuildService service = new RebuildService(
                new FakeSnapshotSource(batches, 84, 84), store, writer);
        long taskId = service.rebuild();

        assertTrue(taskId > 0);
        assertEquals(1, store.objects().size());
        assertEquals(globalIdBefore, store.objects().get(0).globalId(), "全局 ID 重建后稳定不变");
        assertEquals(attributesBefore, store.objects().get(0).attributes(), "当前属性不变");
        assertEquals(1, store.relations().size());
        assertEquals(relationIdBefore, store.relations().get(0).relationId(), "关系身份不变");
        assertEquals(historyBefore, store.changeHistoryCount(), "变更历史不得减少");
        assertEquals(processedBefore, store.processedEventCount(), "去重/消费进度不得丢失");
        assertEquals(tasksBefore + 1, store.syncTaskCount(), "重建追加 1 条可追溯运行记录");
        assertEquals(errorsBefore, store.errorLogCount(), "错误记录不得减少");
        assertTrue(store.platformRevision() > revisionBefore, "平台版本只增不减");
        assertEquals("SUCCESS", store.syncTasks().get(store.syncTasks().size() - 1).get("status"));
    }
}
