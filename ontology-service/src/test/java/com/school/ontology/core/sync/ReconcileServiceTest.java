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

    @Test
    void 对账字段差异输出真实字段级明细含label与前后值() {
        InMemoryProjectionStore store = new InMemoryProjectionStore();
        ProjectionWriter writer = new ProjectionWriter(store, TestFixtures.minimalRegistry());
        String gid = TestFixtures.gid("student", "2");

        // 投影：旧姓名 + 旧学号；来源：新姓名 + 新学号（两个字段都被直改库改掉）。
        ProjectionRow old = TestFixtures.object("student", "2", 84,
                Map.of("name", "旧姓名", "studentNo", "S-0001"));
        store.upsertObject(old);
        ProjectionRow want = TestFixtures.object("student", "2", 90,
                Map.of("name", "新姓名", "studentNo", "S-0002"));
        LinkedHashMap<String, SnapshotBatch> batches = new LinkedHashMap<>();
        batches.put("student", TestFixtures.batch(want));
        FakeSnapshotSource source = new FakeSnapshotSource(batches, 90, 90);
        ReconcileService service = new ReconcileService(source, store, writer,
                new ModelDefinitionLoader(), new OntologyRepository(null, null, null));

        service.reconcile(false);

        ChangeRecordRow fix = store.lastChange();
        assertEquals("RECONCILE", fix.origin());
        List<Map<String, Object>> fieldEntries = fix.diff().stream()
                .filter(d -> "FIELD_DIFF".equals(d.get("kind")))
                .toList();
        assertEquals(2, fieldEntries.size(), "两个被直改的字段应各产出一条 FIELD_DIFF");
        Map<String, Object> nameDiff = fieldEntries.stream()
                .filter(d -> "name".equals(d.get("field"))).findFirst().orElseThrow();
        assertEquals("姓名", nameDiff.get("label"), "label 必须来自模型定义 displayName");
        assertEquals("旧姓名", nameDiff.get("before"));
        assertEquals("新姓名", nameDiff.get("after"));
        assertEquals(gid, nameDiff.get("globalId"));
        assertEquals(Boolean.TRUE, nameDiff.get("fixed"));
        Map<String, Object> noDiff = fieldEntries.stream()
                .filter(d -> "studentNo".equals(d.get("field"))).findFirst().orElseThrow();
        assertEquals("S-0001", noDiff.get("before"));
        assertEquals("S-0002", noDiff.get("after"));
    }

    @Test
    void 对账字段差异超限时截断并产出汇总条目() {
        InMemoryProjectionStore store = new InMemoryProjectionStore();
        ProjectionWriter writer = new ProjectionWriter(store, TestFixtures.minimalRegistry());
        String gid = TestFixtures.gid("student", "3");

        Map<String, Object> oldAttrs = new LinkedHashMap<>();
        Map<String, Object> newAttrs = new LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) {
            oldAttrs.put("field" + i, "旧" + i);
            newAttrs.put("field" + i, "新" + i);
        }
        store.upsertObject(TestFixtures.object("student", "3", 84, oldAttrs));
        ProjectionRow want = TestFixtures.object("student", "3", 90, newAttrs);
        LinkedHashMap<String, SnapshotBatch> batches = new LinkedHashMap<>();
        batches.put("student", TestFixtures.batch(want));
        FakeSnapshotSource source = new FakeSnapshotSource(batches, 90, 90);
        ReconcileService service = new ReconcileService(source, store, writer,
                new ModelDefinitionLoader(), new OntologyRepository(null, null, null));

        service.reconcile(false);

        ChangeRecordRow fix = store.lastChange();
        List<Map<String, Object>> fieldEntries = fix.diff().stream()
                .filter(d -> "FIELD_DIFF".equals(d.get("kind"))).toList();
        List<Map<String, Object>> summaries = fix.diff().stream()
                .filter(d -> "FIELD_DIFF_SUMMARY".equals(d.get("kind"))).toList();
        assertEquals(5, fieldEntries.size(), "字段级明细必须截断到上限 5 条");
        assertEquals(1, summaries.size());
        assertEquals(gid, summaries.get(0).get("globalId"));
        assertTrue(String.valueOf(summaries.get(0).get("note")).contains("3 个字段变化"),
                "8 个差异 - 5 条明细 = 3 条被收敛，note=" + summaries.get(0).get("note"));
        // 未知字段名（不在模型定义里）回退为字段名本身，不抛异常。
        assertTrue(fieldEntries.stream().allMatch(d -> d.get("label") != null));
    }
}
