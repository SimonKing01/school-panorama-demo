package com.school.ontology.core.sync;

import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.TxExecutor;
import com.school.ontology.core.spi.ConsumeResult;
import com.school.ontology.core.spi.OutboxEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 底座侧事件消费（架构 §4.5）。
 *
 * <p>一次消费在**一个本体库事务**内完成：去重 → 版本比较 → 对象 + 关系 +
 * 对象版本 + 变更历史 + 影响 + 来源引用 + 平台版本 + processed_event，
 * 提交后才返回 ACK。任一步异常整体回滚，processed_event 未落库 →
 * 投递器重试 / 重启后从断点续处理，无重复无遗漏。</p>
 */
@Component
public class EventConsumer {

    private final ProjectionStore store;
    private final TxExecutor tx;
    private final ProjectionWriter writer;
    private final EventSnapshotResolver resolver;

    public EventConsumer(ProjectionStore store, TxExecutor tx, ProjectionWriter writer,
                         EventSnapshotResolver resolver) {
        this.store = store;
        this.tx = tx;
        this.writer = writer;
        this.resolver = resolver;
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
        return ConsumeResult.persisted(event.eventId(), event.revision());
    }
}
