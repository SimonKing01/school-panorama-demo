package com.school.ontology.controller;

import com.school.ontology.common.Resp;
import com.school.ontology.core.spi.ConsumeResult;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.core.sync.EventConsumer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部事件接收端点：原业务 OutboxDispatcher → 本体服务唯一异步写入通道。
 *
 * <p><b>信任边界（主理人裁决 1）</b>：本端点仅容器内 / 本机可达，不暴露给浏览器，
 * 也不做鉴权；因此<b>允许人工注入构造事件</b>（重复 eventId、较旧 revision 晚到、
 * DELETE 墓碑等验收三故障注入场景），<b>不校验</b> eventId 是否已存在于业务库
 * outbox_events、也不校验 revision 的真实性。去重与旧版本防护完全由
 * {@code ont_processed_event} 唯一键 + 对象 {@code applied_revision} 版本比较保证。</p>
 *
 * <p>ACK 在本体库单事务提交之后才返回 {@code persisted=true}；
 * 已处理 / 旧事件同样 ACK 但不重复落库；处理失败返回 500 让投递器退避重试，
 * 业务事务不受影响。事件 revision 与对象 applied_revision 均为业务
 * {@code system_revision.revision} 口径，与前端短轮询用的
 * {@code ont_platform_revision}（只增不减）严格分离，绝不互相覆盖。</p>
 */
@RestController
@RequestMapping("/internal")
public class InternalEventController {

    private final EventConsumer consumer;

    public InternalEventController(EventConsumer consumer) {
        this.consumer = consumer;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody Map<String, Object> body) {
        String eventId = text(body.get("eventId"));
        if (eventId == null) {
            return ResponseEntity.badRequest().body(Resp.map(
                    "ack", false, "eventId", null, "error", "eventId 不可为空"));
        }
        OutboxEvent event = new OutboxEvent(
                eventId,
                text(body.get("batchId")),
                text(body.get("aggregateType")),
                text(body.get("aggregateId")),
                text(body.get("changeKind")),
                number(body.get("revision")),
                asMap(body.get("payload")),
                parseTime(body.get("businessTime")),
                parseTime(body.get("occurredAt")),
                text(body.get("status")),
                (int) number(body.get("attempts")));
        ConsumeResult result = consumer.consume(event);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ack", result.ack());
        response.put("eventId", eventId);
        if (result.ack()) {
            response.put("persisted", result.persisted());
            response.put("revision", result.revision());
            if (result.skipped() != null) {
                response.put("skipped", result.skipped());
            }
            return ResponseEntity.ok(response);
        }
        response.put("persisted", false);
        response.put("error", result.error());
        return ResponseEntity.status(500).body(response);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : Map.of();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static LocalDateTime parseTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Resp.parseTs(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
