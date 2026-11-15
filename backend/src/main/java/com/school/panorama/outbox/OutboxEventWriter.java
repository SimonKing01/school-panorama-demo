package com.school.panorama.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.panorama.common.Resp;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 各业务写入口在**同一个本地事务**内追加 Outbox 事件的统一入口。
 *
 * <p>调用顺序硬约束：业务更新 → change_records（若有）→ bumpRevision()
 * → 读回 revision → append(...) → 事务提交。事件里的 revision 与事务一致，
 * businessTime 取演示业务时间，occurred_at 由数据库 now() 生成（真实处理时间）。</p>
 */
@Component
public class OutboxEventWriter {

    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * @param changeKind    ADJUST / BATCH_ADJUST / CLOCK / SYNC_TODAY / RESET / DELETE
     * @param aggregateType LESSON_SESSION / DEMO_STATE / BATCH / SNAPSHOT
     * @param aggregateId   业务主键，或 'ALL' / 'demo_state'
     * @param batchId       批量接口共用批次 ID；单条事件为 null
     * @param revision      bump 之后读回的 system_revision.revision
     * @param businessTime  演示业务时间（可回拨），不是真实处理时间
     * @param payload       前后状态 / 差异 / 影响 / 原因 / 操作人等（03 契约 §3）
     * @return 事件唯一 ID（UUID）
     */
    public String append(String changeKind, String aggregateType, String aggregateId,
                         String batchId, long revision, LocalDateTime businessTime,
                         Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Outbox 事件负载序列化失败", e);
        }
        OutboxEvent event = new OutboxEvent(eventId, batchId, aggregateType, aggregateId,
                changeKind, revision, json, Resp.ts(businessTime));
        outboxMapper.insert(event);
        return eventId;
    }
}
