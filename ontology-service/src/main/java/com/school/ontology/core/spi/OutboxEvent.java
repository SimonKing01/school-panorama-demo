package com.school.ontology.core.spi;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 原业务 Outbox 事件在底座侧的载体。
 * businessTime = 演示业务时间（可回拨）；occurredAt = 真实入库处理时间（单调）。
 */
public record OutboxEvent(String eventId, String batchId, String aggregateType,
                          String aggregateId, String changeKind, long revision,
                          Map<String, Object> payload, LocalDateTime businessTime,
                          LocalDateTime occurredAt, String status, int attempts) {

    @SuppressWarnings("unchecked")
    public Map<String, Object> payloadObject(String key) {
        Object value = payload.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    public String payloadText(String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
