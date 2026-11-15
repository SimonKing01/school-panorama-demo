package com.school.ontology.core.scheduler;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.core.sync.EventConsumer;
import com.school.ontology.repository.OntologyRepository;
import com.school.ontology.repository.SourceOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 兜底追赶调度：业务侧投递器之外的第二道保险，防止漏投事件。
 *
 * <p>只读业务库 outbox_events（含 DELIVERED，去重表保证幂等），
 * 逐条走与实时消费完全相同的 EventConsumer。游标只前进，不回拨；
 * 全量导入后由数据源快照水位校正。任何异常只记错误日志，不阻塞调度线程，
 * 更不影响原业务。</p>
 */
@Component
public class OutboxCatchUpScheduler {

    private final SourceOutboxRepository outbox;
    private final EventConsumer consumer;
    private final ProjectionStore store;
    private final ModelRegistry registry;
    private final int batchSize;
    private final AtomicLong cursor = new AtomicLong(-1L);

    public OutboxCatchUpScheduler(SourceOutboxRepository outbox, EventConsumer consumer,
                                  ProjectionStore store, ModelRegistry registry,
                                  @Value("${ontology.sync.catchup-batch:200}") int batchSize) {
        this.outbox = outbox;
        this.consumer = consumer;
        this.store = store;
        this.registry = registry;
        this.batchSize = Math.max(batchSize, 1);
    }

    @Scheduled(fixedDelayString = "${ontology.sync.catchup-ms:2000}")
    public void run() {
        try {
            Map<String, Object> ds = store.dataSource(registry.sourceId());
            long storedWatermark = OntologyRepository.lng(ds.get("snapshot_revision"));
            long from = cursor.get() < 0 ? storedWatermark : cursor.get();
            List<OutboxEvent> events = outbox.eventsAfter(from, batchSize);
            long maxRevision = from;
            for (OutboxEvent event : events) {
                consumer.consume(event);
                maxRevision = Math.max(maxRevision, event.revision());
            }
            cursor.set(maxRevision);

            Map<String, Long> counts = outbox.statusCounts();
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("pendingCount", counts.getOrDefault("pendingCount", 0L));
            update.put("deadCount", counts.getOrDefault("deadCount", 0L));
            update.put("errorCount", counts.getOrDefault("failedCount", 0L));
            store.updateDataSource(registry.sourceId(), update);
        } catch (Exception e) {
            try {
                store.appendErrorLog("CATCHUP", null, e.getMessage());
            } catch (Exception ignored) {
                // 数据库不可用时保持静默，下一轮继续尝试。
            }
        }
    }
}
