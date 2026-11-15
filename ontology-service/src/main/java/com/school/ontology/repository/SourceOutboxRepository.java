package com.school.ontology.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.ontology.core.spi.OutboxEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原业务库 Outbox 的**只读**访问（架构 §11-A）。
 *
 * <p>全部语句仅 SELECT；业务库表由 backend 启动迁移器创建。本体服务停机、
 * 业务库不可达或表尚未创建时，这里只返回空结果/零计数，绝不写业务库、
 * 绝不阻塞业务。用于全量导入末尾的 catchUp 兜底与失败重试入口。</p>
 */
@Repository
public class SourceOutboxRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SourceOutboxRepository(@Qualifier("sourceJdbc") JdbcTemplate jdbc,
                                  ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 水位之后的所有事件（按 revision 升序）。重复事件由 ont_processed_event 去重。 */
    public List<OutboxEvent> eventsAfter(long revision, int limit) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM outbox_events WHERE revision > ? ORDER BY revision ASC LIMIT ?",
                    revision, limit);
            List<OutboxEvent> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(toEvent(row));
            }
            return result;
        } catch (Exception e) {
            // 原业务库不可达 / 已有卷尚未执行迁移器：底座侧静默跳过，业务不受影响。
            return List.of();
        }
    }

    public OutboxEvent byEventId(String eventId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM outbox_events WHERE event_id = ?", eventId);
            return rows.isEmpty() ? null : toEvent(rows.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    public List<Map<String, Object>> list(String status, int limit, int offset) {
        try {
            if (status == null || status.isBlank()) {
                return jdbc.queryForList("SELECT event_id, batch_id, aggregate_type, aggregate_id, "
                        + "change_kind, revision, status, attempts, last_error, business_time, "
                        + "occurred_at, next_retry_at, delivered_at FROM outbox_events "
                        + "ORDER BY revision DESC LIMIT ? OFFSET ?", limit, offset);
            }
            return jdbc.queryForList("SELECT event_id, batch_id, aggregate_type, aggregate_id, "
                    + "change_kind, revision, status, attempts, last_error, business_time, "
                    + "occurred_at, next_retry_at, delivered_at FROM outbox_events "
                    + "WHERE status = ? ORDER BY revision DESC LIMIT ? OFFSET ?",
                    status, limit, offset);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 来源状态用的待处理/失败/死信计数（只读）。 */
    public Map<String, Long> statusCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("pendingCount", 0L);
        result.put("failedCount", 0L);
        result.put("deadCount", 0L);
        result.put("deliveredCount", 0L);
        Map<String, String> statusToViewKey = Map.of(
                "PENDING", "pendingCount",
                "IN_FLIGHT", "pendingCount",
                "FAILED", "failedCount",
                "DEAD", "deadCount",
                "DELIVERED", "deliveredCount");
        try {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT status, count(*) AS cnt FROM outbox_events GROUP BY status")) {
                String status = OntologyRepository.str(row.get("status"));
                String viewKey = status == null ? null : statusToViewKey.get(status);
                if (viewKey != null) {
                    result.merge(viewKey, OntologyRepository.lng(row.get("cnt")), Long::sum);
                }
            }
        } catch (Exception e) {
            // 表不存在时保持零计数。
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private OutboxEvent toEvent(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Object raw = row.get("payload");
        if (raw != null) {
            try {
                payload = objectMapper.readValue(raw.toString(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                        });
            } catch (Exception ignored) {
                payload = new LinkedHashMap<>();
            }
        }
        return new OutboxEvent(
                OntologyRepository.str(row.get("event_id")),
                OntologyRepository.str(row.get("batch_id")),
                OntologyRepository.str(row.get("aggregate_type")),
                OntologyRepository.str(row.get("aggregate_id")),
                OntologyRepository.str(row.get("change_kind")),
                OntologyRepository.lng(row.get("revision")),
                payload,
                OntologyRepository.dateTime(row.get("business_time")),
                OntologyRepository.dateTime(row.get("occurred_at")),
                OntologyRepository.str(row.get("status")),
                (int) OntologyRepository.lng(row.get("attempts")));
    }
}
