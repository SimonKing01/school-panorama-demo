package com.school.ontology.core.sync;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.repository.OntologyRepository;
import com.school.ontology.support.DirectTxExecutor;
import com.school.ontology.support.FakeSnapshotSource;
import com.school.ontology.support.InMemoryProjectionStore;
import com.school.ontology.support.StubEventSnapshotResolver;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验收三-4 / D2：全量导入末尾以 W0 衔接增量；D1：重复导入不产生重复对象。 */
class SyncTaskCatchUpTest {

    @Test
    void 导入期间水位上涨时以起始水位W0衔接增量且重复导入幂等() {
        InMemoryProjectionStore store = new InMemoryProjectionStore();
        ModelRegistry registry = TestFixtures.minimalRegistry();
        ProjectionWriter writer = new ProjectionWriter(store, registry);

        LinkedHashMap<String, SnapshotBatch> batches = new LinkedHashMap<>();
        batches.put("student", TestFixtures.batch(TestFixtures.object("student", "1", 84,
                Map.of("name", "陈一诺"))));
        // W0=84，导入结束时 W1=86，模拟导入期间发生业务写入。
        FakeSnapshotSource source = new FakeSnapshotSource(batches, 84, 86);

        AtomicLong catchUpFrom = new AtomicLong(-1L);
        EventConsumer consumer = new EventConsumer(store, new DirectTxExecutor(), writer,
                new StubEventSnapshotResolver(EventPlan.empty()));
        CatchUpService catchUp = new CatchUpService(null, consumer, store, registry, 200) {
            @Override
            public int catchUp(long fromRevision) {
                catchUpFrom.set(fromRevision);
                return 2;
            }
        };
        SyncTaskService service = new SyncTaskService(source, store, writer, registry,
                catchUp, new OntologyRepository(null, null, null));

        long taskId = service.fullImport();
        assertTrue(taskId > 0);
        assertEquals(84L, catchUpFrom.get(), "阶段 2 必须从快照起始水位 W0 重放增量");
        assertEquals(1, store.objects().size());
        assertEquals("SUCCESS", store.syncTasks().get(0).get("status"));
        assertEquals(86L, store.dataSource(TestFixtures.SOURCE_ID).get("snapshotRevision"));
        assertTrue(store.platformRevision() >= 1L);

        // 连续第二次全量导入：对象数、关系数、全局 ID 不变。
        source.resetWatermarkCalls();
        String gidBefore = store.objects().get(0).globalId();
        service.fullImport();
        assertEquals(1, store.objects().size());
        assertEquals(gidBefore, store.objects().get(0).globalId());
        assertEquals(2, store.syncTaskCount(), "每次导入追加可追溯运行记录，但投影不重复");
    }
}
