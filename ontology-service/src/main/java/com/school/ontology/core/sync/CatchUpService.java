package com.school.ontology.core.sync;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.spi.ConsumeResult;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.repository.SourceOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 增量衔接 / 兜底追赶：读业务库 Outbox 中 revision > 水位 的事件，
 * 逐条调用与实时消费完全相同的 EventConsumer（去重 + 版本比较 + 单事务）。
 *
 * <p>不重：processed_event 去重；不漏：导入期间所有事件都被重放；
 * 旧不覆盖新：revision >= applied_revision 才应用。底座对业务库仍然只读。</p>
 */
@Component
public class CatchUpService {

    private final SourceOutboxRepository outbox;
    private final EventConsumer consumer;
    private final ProjectionStore store;
    private final ModelRegistry registry;
    private final int batchSize;

    public CatchUpService(SourceOutboxRepository outbox, EventConsumer consumer,
                          ProjectionStore store, ModelRegistry registry,
                          @Value("${ontology.sync.catchup-batch:200}") int batchSize) {
        this.outbox = outbox;
        this.consumer = consumer;
        this.store = store;
        this.registry = registry;
        this.batchSize = Math.max(batchSize, 1);
    }

    public int catchUp(long fromRevision) {
        List<OutboxEvent> events = outbox.eventsAfter(fromRevision, batchSize);
        int acked = 0;
        boolean persisted = false;
        for (OutboxEvent event : events) {
            ConsumeResult result = consumer.consume(event);
            if (result.ack()) {
                acked++;
                persisted = persisted || result.persisted();
            }
        }
        if (persisted) {
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("lastSuccessSyncAt", LocalDateTime.now());
            update.put("pendingCount", 0L);
            update.put("status", "SYNCED");
            store.updateDataSource(registry.sourceId(), update);
        }
        return acked;
    }
}
