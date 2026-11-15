package com.school.panorama.outbox;

/**
 * 事务 Outbox 事件实体（业务库 outbox_events 表）。
 *
 * <p>两类时间严格分离：{@code businessTime} 是演示业务时间 demoTime（可回拨，业务事实），
 * {@code occurredAt}/{@code deliveredAt} 是真实处理时间（单调，由数据库 now() 生成）。</p>
 *
 * <p>{@code payload} 以 JSON 字符串保存，投递时再解析成 JSON 对象发给底座；
 * 前后状态、差异、影响、原因与操作人都放在 payload 里。</p>
 */
public class OutboxEvent {

    public String eventId;
    public String batchId;
    public String aggregateType;
    public String aggregateId;
    public String changeKind;
    public long revision;
    public String payload;
    public String businessTime;
    public String occurredAt;
    public String status;
    public int attempts;
    public String nextRetryAt;
    public String lastError;
    public String deliveredAt;
    public String createdAt;

    public OutboxEvent() {
    }

    public OutboxEvent(String eventId, String batchId, String aggregateType, String aggregateId,
                       String changeKind, long revision, String payload, String businessTime) {
        this.eventId = eventId;
        this.batchId = batchId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.changeKind = changeKind;
        this.revision = revision;
        this.payload = payload;
        this.businessTime = businessTime;
        this.status = "PENDING";
        this.attempts = 0;
    }
}
