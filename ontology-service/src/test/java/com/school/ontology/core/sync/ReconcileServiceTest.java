package com.school.ontology.core.sync;

import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.repository.OntologyRepository;
import com.school.ontology.support.FakeSnapshotSource;
import com.school.ontology.support.InMemoryProjectionStore;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D7 / 验收三-6：直改库差异可发现并修复当前投影，且不伪造操作人、发生时间。 */
class ReconcileServiceTest {

    @Test
    void 对账修复当前投影且不伪造操作人与业务时间() {
        InMemoryProjectionStore store = new InMemoryProjectionStore();
        ProjectionWriter writer = new ProjectionWriter(store, TestFixtures.minimalRegistry());
        String gid = TestFixtures.gid("student", "1");

        // 已有投影：来自一次真实业务事件的历史，操作人与业务时间齐全。
        ProjectionRow old = TestFixtures.object("student", "1", 84, Map.of("name", "旧值"));
        store.upsertObject(old);
        store.appendChangeHistory(new ChangeRecordRow("evt-real", gid, "ATTR_CHANGE", "EVENT",
                Map.of(), Map.of(), List.of(), List.of(), List.of(), "真实业务操作",
                "演示管理员", LocalDateTime.of(2026, 11, 14, 10, 15),
                TestFixtures.MAPPING_VERSION));

        // 来源当前状态（模拟绕过业务服务直改库）：属性不同 + 多出一条关系。
        ProjectionRow want = TestFixtures.object("student", "1", 90, Map.of("name", "新值"));
        LinkedHashMap<String, SnapshotBatch> batches = new LinkedHashMap<>();
        batches.put("student", TestFixtures.batch(want,
                TestFixtures.relation("student_in_class", gid,
                        TestFixtures.gid("class", "1"), 90)));
        FakeSnapshotSource source = new FakeSnapshotSource(batches, 90, 90);

        ReconcileService service = new ReconcileService(source, store, writer,
                new ModelDefinitionLoader(), new OntologyRepository(null, null, null));

        long runId = service.reconcile(false);
        assertTrue(runId > 0);
        assertEquals("新值", store.object(gid).attributes().get("name"), "当前投影必须与源一致");
        assertEquals(2, store.changeHistoryCount());
        ChangeRecordRow fix = store.lastChange();
        assertEquals("RECONCILE", fix.origin());
        assertNull(fix.operator(), "对账修复不得伪造操作人");
        assertNull(fix.businessTime(), "对账修复不得伪造业务发生时间");
        assertTrue(fix.reason().contains("非业务操作"));

        // 对账可重复执行：第二次无差异，不再追加历史。
        service.reconcile(false);
        assertEquals(2, store.changeHistoryCount(), "重复对账不得重复写历史");
    }
}
