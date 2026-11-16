package com.school.ontology.core.sync;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.spi.ConsumeResult;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.support.DirectTxExecutor;
import com.school.ontology.support.InMemoryProjectionStore;
import com.school.ontology.support.StubEventSnapshotResolver;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验收三-1（重复事件去重）、验收三-2（旧事件乱序丢弃）与单事务一致性。 */
class EventConsumerIdempotencyTest {

    private final InMemoryProjectionStore store = new InMemoryProjectionStore();
    private final ProjectionWriter writer = new ProjectionWriter(store, TestFixtures.minimalRegistry());
    private final String gid = TestFixtures.gid("lessonSession", "3");

    private EventConsumer consumer(StubEventSnapshotResolver resolver) {
        return new EventConsumer(store, new DirectTxExecutor(), writer, resolver,
                TestFixtures.minimalRegistry());
    }

    private static EventPlan plan(ProjectionRow row, Map<String, Object> changeInfo) {
        RelationInstance relation = TestFixtures.relation("session_taught_by",
                row.globalId(), TestFixtures.gid("teacher", "5"), row.appliedRevision());
        return new EventPlan(List.of(TestFixtures.batch(row, relation)), List.of(), Map.of(),
                List.of(row.globalId()), List.of(), changeInfo, List.of(), List.of());
    }

    @Test
    void 同一事件重复投递不产生重复对象关系与历史() {
        ProjectionRow row = TestFixtures.object("lessonSession", "3", 85,
                Map.of("teacherName", "陈思琪"));
        StubEventSnapshotResolver resolver = new StubEventSnapshotResolver(
                plan(row, Map.of("changeKind", "ADJUST", "reason", "教师参加教研")));
        EventConsumer eventConsumer = consumer(resolver);
        OutboxEvent event = TestFixtures.event("evt-1", 85, "ADJUST");

        ConsumeResult first = eventConsumer.consume(event);
        assertTrue(first.ack());
        assertTrue(first.persisted());
        assertEquals(1, store.objects().size());
        assertEquals(1, store.relations().size());
        assertEquals(1, store.changeHistoryCount());
        assertEquals(1, store.processedEventCount());
        assertEquals(1L, store.platformRevision());

        ConsumeResult second = eventConsumer.consume(event);
        assertTrue(second.ack());
        assertFalse(second.persisted());
        assertEquals("DUPLICATE", second.skipped());
        assertEquals(1, store.objects().size());
        assertEquals(1, store.relations().size());
        assertEquals(1, store.changeHistoryCount());
        assertEquals(1, store.processedEventCount());
        assertEquals(1L, store.platformRevision());
    }

    @Test
    void 较旧事件晚于新事件到达时丢弃且仍ACK() {
        ProjectionRow newer = TestFixtures.object("lessonSession", "3", 85,
                Map.of("teacherName", "陈思琪"));
        StubEventSnapshotResolver resolver = new StubEventSnapshotResolver(
                plan(newer, Map.of("changeKind", "ADJUST")));
        EventConsumer eventConsumer = consumer(resolver);
        eventConsumer.consume(TestFixtures.event("evt-1", 85, "ADJUST"));

        ProjectionRow older = TestFixtures.object("lessonSession", "3", 84,
                Map.of("teacherName", "张明远"));
        resolver.setPlan(plan(older, Map.of("changeKind", "ADJUST")));
        ConsumeResult stale = eventConsumer.consume(TestFixtures.event("evt-2", 84, "ADJUST"));

        assertTrue(stale.ack());
        assertFalse(stale.persisted());
        assertEquals("STALE", stale.skipped());
        assertEquals("陈思琪", store.object(gid).attributes().get("teacherName"),
                "最终状态必须是较新事件，不被旧事件覆盖");
        assertEquals(85L, store.appliedRevision(gid));
        assertEquals(1, store.changeHistoryCount());
        assertEquals(2, store.processedEventCount(), "旧事件也记入去重表，避免反复重放");
    }

    @Test
    void 较新事件可以覆盖较旧状态() {
        ProjectionRow older = TestFixtures.object("lessonSession", "3", 85,
                Map.of("teacherName", "陈思琪"));
        StubEventSnapshotResolver resolver = new StubEventSnapshotResolver(
                plan(older, Map.of("changeKind", "ADJUST")));
        EventConsumer eventConsumer = consumer(resolver);
        eventConsumer.consume(TestFixtures.event("evt-1", 85, "ADJUST"));

        ProjectionRow newer = TestFixtures.object("lessonSession", "3", 86,
                Map.of("teacherName", "王小明"));
        resolver.setPlan(plan(newer, Map.of("changeKind", "ADJUST")));
        ConsumeResult result = eventConsumer.consume(TestFixtures.event("evt-2", 86, "ADJUST"));

        assertTrue(result.persisted());
        assertEquals("王小明", store.object(gid).attributes().get("teacherName"));
        assertEquals(2, store.changeHistoryCount());
        assertEquals(2L, store.platformRevision());
    }

    /**
     * 缺陷回归：事件消费必须推进 snapshot_revision 与 last_success_sync_at
     * （原实现只有全量导入才推进水位，事件同步永远是旧值）。
     */
    @Test
    void 事件成功消费推进快照水位且只增不减() {
        ProjectionRow row = TestFixtures.object("lessonSession", "3", 90,
                Map.of("teacherName", "陈思琪"));
        StubEventSnapshotResolver resolver = new StubEventSnapshotResolver(
                plan(row, Map.of("changeKind", "ADJUST")));
        EventConsumer eventConsumer = consumer(resolver);

        ConsumeResult first = eventConsumer.consume(TestFixtures.event("evt-1", 90, "ADJUST"));
        assertTrue(first.persisted());
        Map<String, Object> dataSource = store.dataSource(TestFixtures.SOURCE_ID);
        assertEquals(90L, longOf(dataSource.get("snapshotRevision")), "成功消费必须推进快照水位");
        assertTrue(dataSource.get("lastSuccessSyncAt") != null, "成功消费必须刷新最后成功同步时间");

        // 旧事件晚到：既不能回退水位，也不能把同步时间冲掉。
        Object syncedAt = dataSource.get("lastSuccessSyncAt");
        ProjectionRow older = TestFixtures.object("lessonSession", "3", 88,
                Map.of("teacherName", "张明远"));
        resolver.setPlan(plan(older, Map.of("changeKind", "ADJUST")));
        ConsumeResult stale = eventConsumer.consume(TestFixtures.event("evt-2", 88, "ADJUST"));
        assertFalse(stale.persisted());

        Map<String, Object> after = store.dataSource(TestFixtures.SOURCE_ID);
        assertEquals(90L, longOf(after.get("snapshotRevision")), "水位只增不减，旧事件不得回退");
        assertEquals(syncedAt, after.get("lastSuccessSyncAt"), "被丢弃的事件不刷新同步时间");
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }
}
