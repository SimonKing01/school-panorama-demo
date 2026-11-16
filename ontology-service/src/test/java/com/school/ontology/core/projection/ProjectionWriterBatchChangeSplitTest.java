package com.school.ontology.core.projection;

import com.school.ontology.core.spi.ConsumeResult;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.core.sync.EventConsumer;
import com.school.ontology.support.DirectTxExecutor;
import com.school.ontology.support.InMemoryProjectionStore;
import com.school.ontology.support.StubEventSnapshotResolver;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷回归（P5，阻断级）：多条目事件的变更历史被合并成 1 条，且 diff 混入其他对象的变化。
 *
 * <p>批量写入口（一次调 2 条课的 {@code BATCH_ADJUST}）只发 1 条 Outbox 事件，
 * 负载顶层的 {@code diff} 是 {@code allDiffs}（所有条目合并）。旧实现恒定只写 1 条变更记录、
 * 锚点取 {@code selfGlobalIds.get(0)}，于是 LS-001 的「近期变化」里出现了只属于 LS-002 的
 * 「李静怡 → 周文博」，而业务侧 {@code change_records} 是干净的逐条 2 条。</p>
 *
 * <p>修复后：{@code changeUnits.size() > 1} 时按条目拆成多条记录，每条只带自己的
 * before/after/diff，关系增删按端点过滤；单条目事件完全沿用原有整批语义（不许回归）。</p>
 */
class ProjectionWriterBatchChangeSplitTest {

    private static final String LS1 = TestFixtures.gid("lessonSession", "1");
    private static final String LS2 = TestFixtures.gid("lessonSession", "2");
    private static final String OLD_TEACHER_1 = TestFixtures.gid("teacher", "5");
    private static final String NEW_TEACHER_1 = TestFixtures.gid("teacher", "7");
    private static final String OLD_TEACHER_2 = TestFixtures.gid("teacher", "9");
    private static final String NEW_TEACHER_2 = TestFixtures.gid("teacher", "12");
    private static final String CLASSROOM = TestFixtures.gid("classroom", "1");
    private static final String TAUGHT_BY = "session_taught_by";

    private final InMemoryProjectionStore store = new InMemoryProjectionStore();
    private final ProjectionWriter writer =
            new ProjectionWriter(store, TestFixtures.minimalRegistry());

    // ── 数据构造 ─────────────────────────────────────────────────────────────

    /** 一条业务侧的字段差集（与 AdjustService 一致的 field/label/before/after 结构）。 */
    private static Map<String, Object> diff(String field, String before, String after) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", field);
        item.put("label", "授课教师");
        item.put("before", before);
        item.put("after", after);
        return item;
    }

    private static ProjectionRow lesson(String pk, String teacherName, long revision) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("teacherName", teacherName);
        return TestFixtures.object("lessonSession", pk, revision, attributes);
    }

    /** 预置两条授课安排：LS-1 张明远、LS-2 李静怡，各自一条「授课教师」出边。 */
    private void seed() {
        store.upsertObject(lesson("1", "张明远", 84L));
        store.upsertObject(lesson("2", "李静怡", 84L));
        store.upsertRelation(TestFixtures.relation(TAUGHT_BY, LS1, OLD_TEACHER_1, 84L));
        store.upsertRelation(TestFixtures.relation(TAUGHT_BY, LS2, OLD_TEACHER_2, 84L));
    }

    /** 一次批量调课：LS-1 → 陈思琪、LS-2 → 周文博，一个事件两个条目。 */
    private static EventPlan batchPlan(long revision) {
        List<SnapshotBatch> batches = List.of(
                TestFixtures.batch(lesson("1", "陈思琪", revision),
                        TestFixtures.relation(TAUGHT_BY, LS1, NEW_TEACHER_1, revision)),
                TestFixtures.batch(lesson("2", "周文博", revision),
                        TestFixtures.relation(TAUGHT_BY, LS2, NEW_TEACHER_2, revision)));

        List<Map<String, Object>> unitsDiff = new ArrayList<>();
        unitsDiff.add(diff("teacherName", "张明远", "陈思琪"));
        unitsDiff.add(diff("teacherName", "李静怡", "周文博"));

        Map<String, Object> before1 = new LinkedHashMap<>();
        before1.put("teacherName", "张明远");
        Map<String, Object> after1 = new LinkedHashMap<>();
        after1.put("teacherName", "陈思琪");
        Map<String, Object> before2 = new LinkedHashMap<>();
        before2.put("teacherName", "李静怡");
        Map<String, Object> after2 = new LinkedHashMap<>();
        after2.put("teacherName", "周文博");

        List<ChangeUnit> units = List.of(
                new ChangeUnit(LS1, before1, after1, List.of(diff("teacherName", "张明远", "陈思琪")),
                        "lesson_sessions", "1", "批量调课"),
                new ChangeUnit(LS2, before2, after2, List.of(diff("teacherName", "李静怡", "周文博")),
                        "lesson_sessions", "2", "批量调课"));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("changeKind", "BATCH_ADJUST");
        // 顶层 diff 是 allDiffs（业务侧就是这样发的）：两个条目合并。
        info.put("diff", unitsDiff);
        info.put("operator", "演示管理员");
        info.put("reason", "批量调课");
        info.put("mappingVersion", TestFixtures.MAPPING_VERSION);

        return new EventPlan(batches, List.of(), Map.of(), List.of(LS1, LS2),
                List.of(CLASSROOM), info, List.of(), units);
    }

    // ── 断言辅助 ─────────────────────────────────────────────────────────────

    /** 把一条记录的 diff 压平成可读文本，便于断言「不含别人的字段值」。 */
    private static String diffText(ChangeRecordRow change) {
        return String.valueOf(change.diff());
    }

    private static boolean touches(Map<String, Object> edge, String globalId) {
        return globalId.equals(edge.get("fromGlobalId")) || globalId.equals(edge.get("toGlobalId"));
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    void 两条目事件拆成两条记录且各自diff只含自己() {
        seed();
        writer.applyEvent(TestFixtures.event("evt-batch-1", 85L, "BATCH_ADJUST"), batchPlan(85L));

        List<ChangeRecordRow> changes = store.changeHistory();
        assertEquals(2, changes.size(), "两条目事件必须写 2 条变更记录（业务侧也是 2 条）");

        ChangeRecordRow first = changes.get(0);
        ChangeRecordRow second = changes.get(1);
        assertEquals(LS1, first.globalId(), "第 1 条记录挂在 LS-1 名下");
        assertEquals(LS2, second.globalId(), "第 2 条记录挂在 LS-2 名下");
        assertEquals("BATCH_ADJUST", first.changeKind());
        assertEquals("演示管理员", first.operator());

        assertEquals(1, first.diff().size(), "每条记录只带自己 1 条字段差集");
        assertEquals(1, second.diff().size());
        String firstDiff = diffText(first);
        String secondDiff = diffText(second);
        assertTrue(firstDiff.contains("张明远") && firstDiff.contains("陈思琪"),
                "LS-1 的 diff 必须是 张明远 → 陈思琪：" + firstDiff);
        assertFalse(firstDiff.contains("李静怡") || firstDiff.contains("周文博"),
                "LS-1 的 diff 不能混入 LS-2 的字段值：" + firstDiff);
        assertTrue(secondDiff.contains("李静怡") && secondDiff.contains("周文博"),
                "LS-2 的 diff 必须是 李静怡 → 周文博：" + secondDiff);
        assertFalse(secondDiff.contains("张明远") || secondDiff.contains("陈思琪"),
                "LS-2 的 diff 不能混入 LS-1 的字段值：" + secondDiff);

        // before/after 也必须按条目切分，否则详情页会显示别人的字段。
        assertEquals("张明远", first.before().get("teacherName"));
        assertEquals("陈思琪", first.after().get("teacherName"));
        assertEquals("李静怡", second.before().get("teacherName"));
        assertEquals("周文博", second.after().get("teacherName"));
        assertEquals("批量调课", second.reason(), "条目没写 reason 时回退事件级原因");
    }

    @Test
    void 每条记录的关系增删只含自己的端点() {
        seed();
        writer.applyEvent(TestFixtures.event("evt-batch-1", 85L, "BATCH_ADJUST"), batchPlan(85L));

        List<ChangeRecordRow> changes = store.changeHistory();
        ChangeRecordRow first = changes.get(0);
        ChangeRecordRow second = changes.get(1);

        assertEquals(1, first.relationsAdded().size(), "整批 2 条新增边，LS-1 只应拿到自己那条");
        assertEquals(1, first.relationsRemoved().size(), "LS-1 只应拿到自己那条被结束的边");
        for (Map<String, Object> edge : first.relationsAdded()) {
            assertTrue(touches(edge, LS1), "LS-1 记录里的新增边必须触及 LS-1：" + edge);
        }
        for (Map<String, Object> edge : first.relationsRemoved()) {
            assertTrue(touches(edge, LS1), "LS-1 记录里的解除边必须触及 LS-1：" + edge);
        }
        for (Map<String, Object> edge : second.relationsAdded()) {
            assertTrue(touches(edge, LS2), "LS-2 记录里的新增边必须触及 LS-2：" + edge);
        }
        for (Map<String, Object> edge : second.relationsRemoved()) {
            assertTrue(touches(edge, LS2), "LS-2 记录里的解除边必须触及 LS-2：" + edge);
        }
        // 两条记录合起来才是整批差集，不能丢边。
        assertEquals(2, first.relationsAdded().size() + second.relationsAdded().size());
        assertEquals(2, first.relationsRemoved().size() + second.relationsRemoved().size());
    }

    @Test
    void 同批条目不互相标成对方的相关对象() {
        seed();
        writer.applyEvent(TestFixtures.event("evt-batch-1", 85L, "BATCH_ADJUST"), batchPlan(85L));

        List<ChangeRecordRow> changes = store.changeHistory();
        assertEquals(2, changes.size());

        // 变化列表按 impact 命中：若把同批兄弟写成 DERIVED_RELATED，
        // LS-001 的「近期变化」仍会列出 LS-002 那条记录（缺陷复现），所以必须不写。
        List<String> firstImpacts = gidsOf(store.impactsOf(1L));
        List<String> secondImpacts = gidsOf(store.impactsOf(2L));
        assertTrue(firstImpacts.contains(LS1), "本条的自身对象必须标 SELF_CHANGED");
        assertFalse(firstImpacts.contains(LS2), "不得把同批另一条标成本条的相关对象：" + firstImpacts);
        assertTrue(secondImpacts.contains(LS2));
        assertFalse(secondImpacts.contains(LS1), "不得把同批另一条标成本条的相关对象：" + secondImpacts);
        // 真正的派生相关对象（教室的排课数被重算）仍要保留。
        assertTrue(firstImpacts.contains(CLASSROOM), "派生相关对象仍要写入：" + firstImpacts);
    }

    private static List<String> gidsOf(List<Map<String, Object>> impacts) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> impact : impacts) {
            result.add(String.valueOf(impact.get("globalId")));
        }
        return result;
    }

    @Test
    void 单条目事件仍只写一条记录() {
        seed();
        // 单条 ADJUST：一个 self、一条 diff、一个变更单元（没有 items[] 时 itemsOf 回退 payload）。
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("changeKind", "ADJUST");
        info.put("diff", List.of(diff("teacherName", "张明远", "陈思琪")));
        info.put("operator", "演示管理员");
        info.put("reason", "教师替换");
        EventPlan plan = new EventPlan(
                List.of(TestFixtures.batch(lesson("1", "陈思琪", 85L),
                        TestFixtures.relation(TAUGHT_BY, LS1, NEW_TEACHER_1, 85L))),
                List.of(), Map.of(), List.of(LS1), List.of(), info, List.of(),
                List.of(new ChangeUnit(LS1, Map.of("teacherName", "张明远"),
                        Map.of("teacherName", "陈思琪"),
                        List.of(diff("teacherName", "张明远", "陈思琪")),
                        "lesson_sessions", "1", "教师替换")));

        writer.applyEvent(TestFixtures.event("evt-single-1", 85L, "ADJUST"), plan);

        assertEquals(1, store.changeHistoryCount(), "单条目事件必须仍然只写 1 条记录");
        ChangeRecordRow change = store.lastChange();
        assertEquals(LS1, change.globalId());
        assertEquals(1, change.relationsAdded().size());
        assertEquals(1, change.relationsRemoved().size());
    }

    @Test
    void 同一批次事件重复消费不新增记录() {
        seed();
        EventConsumer consumer = new EventConsumer(store, new DirectTxExecutor(), writer,
                new StubEventSnapshotResolver(batchPlan(85L)), TestFixtures.minimalRegistry());
        OutboxEvent event = TestFixtures.event("evt-batch-1", 85L, "BATCH_ADJUST");

        ConsumeResult first = consumer.consume(event);
        assertTrue(first.ack());
        assertTrue(first.persisted());
        assertEquals(2, store.changeHistoryCount(), "首次消费写 2 条记录");
        assertEquals(1, store.processedEventCount());

        ConsumeResult second = consumer.consume(event);
        assertTrue(second.ack());
        assertFalse(second.persisted());
        assertEquals("DUPLICATE", second.skipped());
        assertEquals(2, store.changeHistoryCount(), "重复消费不得把 2 条翻成 4 条");
        assertEquals(1, store.processedEventCount());
    }
}
