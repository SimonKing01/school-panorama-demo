package com.school.ontology.core.sync;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.TxExecutor;
import com.school.ontology.core.spi.ConsumeResult;
import com.school.ontology.core.spi.OutboxEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 底座侧事件消费（架构 §4.5）。
 *
 * <p>一次消费在**一个本体库事务**内完成：去重 → 版本比较 → 对象 + 关系 +
 * 对象版本 + 变更历史 + 影响 + 来源引用 + 数据源水位 + 平台版本 + processed_event，
 * 提交后才返回 ACK。任一步异常整体回滚，processed_event 未落库 →
 * 投递器重试 / 重启后从断点续处理，无重复无遗漏。</p>
 */
@Component
public class EventConsumer {

    private final ProjectionStore store;
    private final TxExecutor tx;
    private final ProjectionWriter writer;
    private final EventSnapshotResolver resolver;
    private final ModelRegistry registry;

    public EventConsumer(ProjectionStore store, TxExecutor tx, ProjectionWriter writer,
                         EventSnapshotResolver resolver, ModelRegistry registry) {
        this.store = store;
        this.tx = tx;
        this.writer = writer;
        this.resolver = resolver;
        this.registry = registry;
    }

    public ConsumeResult consume(OutboxEvent event) {
        try {
            return tx.inTransaction(() -> consumeInTransaction(event));
        } catch (Exception e) {
            try {
                store.appendErrorLog("CONSUME", event.eventId(), e.getMessage());
            } catch (Exception ignored) {
                // 错误日志是尽力而为，不能掩盖原始失败原因。
            }
            return ConsumeResult.failure(event.eventId(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private ConsumeResult consumeInTransaction(OutboxEvent event) {
        // 1. 幂等去重：event_id 唯一 + ont_processed_event；冲突直接 ACK。
        if (!store.markProcessed(event.eventId(), event.revision())) {
            return ConsumeResult.skipped(event.eventId(), "DUPLICATE", event.revision());
        }
        EventPlan plan;
        try {
            plan = resolver.plan(event);
        } catch (Exception e) {
            throw new IllegalStateException("事件计划解析失败：" + e.getMessage(), e);
        }

        // 2. 版本比较：旧事件晚于新事件到达时丢弃（仍 ACK，避免反复重放）。
        List<String> affected = new ArrayList<>(plan.selfGlobalIds());
        affected.addAll(plan.tombstoneGlobalIds());
        for (String globalId : affected) {
            if (event.revision() < store.appliedRevision(globalId)) {
                return ConsumeResult.skipped(event.eventId(), "STALE", event.revision());
            }
        }

        // 3. 单事务投影落库（含平台版本 bump 与 processed_event 提交）。
        writer.applyEvent(event, plan);

        // 4. 事件已真正应用到投影：推进数据源水位 + 记录真实成功同步时间。
        //    与消费同事务，避免「投影已落库但水位没跟上」（旧实现只有全量导入才推进水位），
        //    也避免「事务回滚了却写了水位」。DUPLICATE / STALE 在上面已提前返回，不推进。
        advanceWatermark(event.revision());

        return ConsumeResult.persisted(event.eventId(), event.revision());
    }

    /**
     * 水位只增不减：取 max(现有 snapshot_revision, 本事件 revision)，
     * 旧事件晚到（已在本对象的 applied_revision 比较中丢弃）也不会让水位回退。
     * lastSuccessSyncAt 用真实处理时间，与演示业务时间（demoTime）严格分离。
     */
    private void advanceWatermark(long revision) {
        String sourceId = registry.sourceId();
        long current = snapshotRevisionOf(store.dataSource(sourceId));
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("snapshotRevision", Math.max(current, revision));
        update.put("lastSuccessSyncAt", LocalDateTime.now());
        store.updateDataSource(sourceId, update);
    }

    /** 兼容读取：JDBC 返回列名 snapshot_revision，内存实现按驼峰键存。 */
    private static long snapshotRevisionOf(Map<String, Object> dataSource) {
        Object value = dataSource.containsKey("snapshot_revision")
                ? dataSource.get("snapshot_revision")
                : dataSource.get("snapshotRevision");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
