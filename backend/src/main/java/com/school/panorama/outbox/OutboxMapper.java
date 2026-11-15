package com.school.panorama.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * outbox_events 的注解式 SQL 等价实现（JdbcTemplate）。
 *
 * <p>为什么不用 MyBatis 接口：启动类只扫描 {@code com.school.panorama.mapper}，
 * 而本类按分工位于 {@code com.school.panorama.outbox}；同时领取事件需要
 * {@code FOR UPDATE SKIP LOCKED} 等精确 SQL。JdbcTemplate 由现有 starter 传递引入，
 * 不新增依赖，且会自动参与调用方所在的 Spring 事务（业务更新与事件同事务的关键）。</p>
 *
 * <p>表由 {@link OutboxSchemaMigrator} 启动时幂等创建，适用于已有数据卷。</p>
 */
@Repository
public class OutboxMapper {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String COLUMNS = """
            event_id, batch_id, aggregate_type, aggregate_id, change_kind, revision,
            payload::text AS payload,
            to_char(business_time, 'YYYY-MM-DD"T"HH24:MI:SS') AS business_time,
            to_char(occurred_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS occurred_at,
            status, attempts,
            to_char(next_retry_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS next_retry_at,
            last_error,
            to_char(delivered_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS delivered_at,
            to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS created_at
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<OutboxEvent> rowMapper = new OutboxEventRowMapper();

    public OutboxMapper(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 在业务本地事务内插入事件；occurred_at 用数据库 now()，绝不取演示时间。 */
    public void insert(OutboxEvent event) {
        jdbc.update("""
                INSERT INTO outbox_events
                  (event_id, batch_id, aggregate_type, aggregate_id, change_kind, revision,
                   payload, business_time, occurred_at, status, attempts, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::timestamp, now(), 'PENDING', 0, now())
                """,
                event.eventId, event.batchId, event.aggregateType, event.aggregateId,
                event.changeKind, event.revision, event.payload,
                event.businessTime);
    }

    /**
     * 领取待投递事件（必须在短事务内调用，FOR UPDATE SKIP LOCKED 才生效）。
     *
     * <p>IN_FLIGHT 在领取时写入一个 30 秒后的「看门狗时间」到 next_retry_at：
     * 若服务在 HTTP 投递前崩溃，该事件超时后会被下一轮重新领取，实现「重启从断点续处理」；
     * 正常在途的 IN_FLIGHT 因 next_retry_at > now() 不会被重复领取。DEAD 需管理端点重放。</p>
     */
    public List<OutboxEvent> claim(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM outbox_events "
                        + "WHERE status IN ('PENDING', 'FAILED', 'IN_FLIGHT') "
                        + "AND (next_retry_at IS NULL OR next_retry_at <= now()) "
                        + "ORDER BY revision ASC LIMIT ? FOR UPDATE SKIP LOCKED",
                rowMapper, limit);
    }

    public void markInFlight(String eventId) {
        jdbc.update("UPDATE outbox_events SET status = 'IN_FLIGHT', "
                + "next_retry_at = now() + INTERVAL '30 seconds' WHERE event_id = ?", eventId);
    }

    /** 只有在底座返回 {ack:true}（已持久化）后才确认，杜绝「以为同步了其实没落库」。 */
    public void markDelivered(String eventId) {
        jdbc.update("UPDATE outbox_events SET status = 'DELIVERED', delivered_at = now(), "
                + "last_error = NULL, next_retry_at = NULL WHERE event_id = ?", eventId);
    }

    /** 失败退避；超过阈值置 DEAD，等待管理端点重放，不自动无限重试。 */
    public void markFailed(String eventId, int attempts, LocalDateTime nextRetryAt,
                           String error, boolean dead) {
        jdbc.update("UPDATE outbox_events SET status = ?, attempts = ?, "
                        + "next_retry_at = ?::timestamp, last_error = ? WHERE event_id = ?",
                dead ? "DEAD" : "FAILED", attempts,
                nextRetryAt == null ? null : nextRetryAt.format(TS), error, eventId);
    }

    public OutboxEvent findById(String eventId) {
        List<OutboxEvent> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM outbox_events WHERE event_id = ?", rowMapper, eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> list(String status, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM outbox_events "
                + (status == null || status.isBlank() ? "" : "WHERE status = ? ")
                + "ORDER BY revision DESC, created_at DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> rows = status == null || status.isBlank()
                ? jdbc.queryForList(sql, limit, offset)
                : jdbc.queryForList(sql, status, limit, offset);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("eventId", row.get("event_id"));
            view.put("batchId", row.get("batch_id"));
            view.put("aggregateType", row.get("aggregate_type"));
            view.put("aggregateId", row.get("aggregate_id"));
            view.put("changeKind", row.get("change_kind"));
            view.put("revision", row.get("revision"));
            view.put("status", row.get("status"));
            view.put("attempts", row.get("attempts"));
            view.put("lastError", row.get("last_error"));
            view.put("businessTime", row.get("business_time"));
            view.put("occurredAt", row.get("occurred_at"));
            view.put("nextRetryAt", row.get("next_retry_at"));
            view.put("deliveredAt", row.get("delivered_at"));
            result.add(view);
        }
        return result;
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "IN_FLIGHT", "FAILED", "DELIVERED", "DEAD")) {
            result.put(status, 0L);
        }
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT status, count(*) AS cnt FROM outbox_events GROUP BY status")) {
            Object key = row.get("status");
            Object cnt = row.get("cnt");
            if (key != null && cnt instanceof Number number) {
                result.put(String.valueOf(key), number.longValue());
            }
        }
        return result;
    }

    /** 管理端点重放：只重新排队 DEAD/FAILED 事件，不修改已 DELIVERED 的记录。 */
    public int requeue(String eventId) {
        return jdbc.update("UPDATE outbox_events SET status = 'PENDING', attempts = 0, "
                        + "next_retry_at = NULL, last_error = NULL "
                        + "WHERE event_id = ? AND status IN ('DEAD', 'FAILED')",
                eventId);
    }

    private static final class OutboxEventRowMapper implements RowMapper<OutboxEvent> {

        @Override
        public OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            OutboxEvent e = new OutboxEvent();
            e.eventId = rs.getString("event_id");
            e.batchId = rs.getString("batch_id");
            e.aggregateType = rs.getString("aggregate_type");
            e.aggregateId = rs.getString("aggregate_id");
            e.changeKind = rs.getString("change_kind");
            e.revision = rs.getLong("revision");
            e.payload = rs.getString("payload");
            e.businessTime = rs.getString("business_time");
            e.occurredAt = rs.getString("occurred_at");
            e.status = rs.getString("status");
            e.attempts = rs.getInt("attempts");
            e.nextRetryAt = rs.getString("next_retry_at");
            e.lastError = rs.getString("last_error");
            e.deliveredAt = rs.getString("delivered_at");
            e.createdAt = rs.getString("created_at");
            return e;
        }
    }

    /** 供时间戳列统一格式化（JdbcTemplate 也可直接绑定 LocalDateTime，此处保留口径说明）。 */
    public static String format(LocalDateTime value) {
        return value == null ? null : value.format(TS);
    }
}
