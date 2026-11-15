package com.school.ontology.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.ConditionUpsertSql;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.SourceRefRow;
import com.school.ontology.core.spi.RelationInstance;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本体库（唯一写者）的 SQL 访问层。
 *
 * <p>所有投影写入使用「稳定全局 ID + ON CONFLICT 条件 UPSERT」；JSONB 统一以
 * {@code ?::jsonb} 绑定 Jackson 序列化后的字符串，与现有业务 json() 风格一致。
 * 查询侧的动态列名来自模型定义，经 {@link #safeIdentifier(String)} 白名单校验。</p>
 */
@Repository
public class OntologyRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_JSON_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PlatformRevisionMapper revisionMapper;

    public OntologyRepository(@Qualifier("ontologyJdbc") JdbcTemplate jdbc,
                              ObjectMapper objectMapper,
                              PlatformRevisionMapper revisionMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.revisionMapper = revisionMapper;
    }

    // ── JSONB 工具 ──────────────────────────────────────────────────────────

    public String json(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 JSONB 内容", e);
        }
    }

    public Map<String, Object> parseMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        try {
            String text = value.toString();
            if (text.isBlank()) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 JSONB 对象", e);
        }
    }

    public List<Map<String, Object>> parseList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        try {
            String text = value.toString();
            if (text.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(text, new TypeReference<ArrayList<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 JSONB 数组", e);
        }
    }

    public static String safeIdentifier(String name) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("非法标识符：" + name);
        }
        return name;
    }

    public static String safeJsonKey(String name) {
        if (name == null || !SAFE_JSON_KEY.matcher(name).matches()) {
            throw new IllegalArgumentException("非法属性名：" + name);
        }
        return name;
    }

    // ── 平台版本（只增不减） ────────────────────────────────────────────────

    public long platformRevision() {
        try {
            return revisionMapper.revision();
        } catch (Exception e) {
            return 0L;
        }
    }

    public long bumpPlatformRevision() {
        int changed = revisionMapper.bump();
        if (changed == 0) {
            jdbc.update("INSERT INTO ont_platform_revision (id, revision) VALUES (1, 1) "
                    + "ON CONFLICT (id) DO UPDATE SET revision = ont_platform_revision.revision + 1, updated_at = now()");
        }
        return platformRevision();
    }

    // ── 消费去重 ────────────────────────────────────────────────────────────

    public boolean insertProcessedEvent(String eventId, long revision) {
        return jdbc.update(ConditionUpsertSql.INSERT_PROCESSED_EVENT, eventId, revision) == 1;
    }

    public boolean isProcessed(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ont_processed_event WHERE event_id = ?", Integer.class, eventId);
        return count != null && count > 0;
    }

    public long processedEventCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ont_processed_event", Long.class);
        return count == null ? 0L : count;
    }

    // ── 当前投影写入 ────────────────────────────────────────────────────────

    public void upsertObject(ProjectionRow row) {
        jdbc.update(ConditionUpsertSql.UPSERT_OBJECT,
                row.globalId(), row.sourceId(), row.objectType(), row.sourcePk(),
                row.title(), json(row.attributes()), row.mappingVersion(),
                row.appliedRevision(), row.deleted());
    }

    public void upsertRelation(RelationInstance relation) {
        jdbc.update(ConditionUpsertSql.UPSERT_RELATION,
                relation.relationId(), relation.relationType(), relation.fromGlobalId(),
                relation.toGlobalId(), relation.reason(), relation.sourceTable(),
                relation.sourcePk(), relation.sourceHash(), relation.mappingVersion(),
                relation.validFrom(), relation.validTo(), relation.active(),
                relation.appliedRevision());
    }

    public void upsertSourceRef(SourceRefRow ref) {
        jdbc.update(ConditionUpsertSql.UPSERT_SOURCE_REF,
                ref.globalId(), ref.sourceId(), ref.sourceTable(), ref.sourcePk(),
                ref.sourceCode(), ref.sourceHash(), ref.mappingVersion(), ref.lastSeenRevision());
    }

    public void appendObjectVersion(String globalId, long revision, Map<String, Object> snapshot,
                                   String eventId) {
        jdbc.update("INSERT INTO ont_object_version (global_id, revision, snapshot, event_id, recorded_at) "
                        + "VALUES (?, ?, ?::jsonb, ?, now())",
                globalId, revision, json(snapshot), eventId);
    }

    public long appendChangeHistory(ChangeRecordRow change) {
        Long id = jdbc.queryForObject("""
                INSERT INTO ont_change_history
                  (event_id, global_id, change_kind, origin, before_json, after_json, diff_json,
                   relations_added, relations_removed, reason, operator, business_time,
                   processed_at, mapping_version)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, ?, ?, now(), ?)
                RETURNING change_id
                """, Long.class,
                change.eventId(), change.globalId(), change.changeKind(), change.origin(),
                json(change.before()), json(change.after()), json(change.diff()),
                json(change.relationsAdded()), json(change.relationsRemoved()),
                change.reason(), change.operator(), change.businessTime(),
                change.mappingVersion());
        return id == null ? -1L : id;
    }

    public void appendImpact(long changeId, String globalId, String role, String basis) {
        jdbc.update("INSERT INTO ont_change_impact (change_id, global_id, role, basis) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (change_id, global_id) DO UPDATE SET "
                        + "role = EXCLUDED.role, basis = EXCLUDED.basis",
                changeId, globalId, role, basis);
    }

    public void deactivateRelation(String relationId, LocalDateTime validTo, long revision) {
        // 版本水位保护：较旧的快照/事件不能把新关系的 applied_revision 拉低。
        jdbc.update("UPDATE ont_relation SET active = FALSE, valid_to = ?, applied_revision = ? "
                        + "WHERE relation_id = ? AND active = TRUE AND ? >= applied_revision",
                validTo, revision, relationId, revision);
    }

    public void tombstoneObject(String globalId, long revision) {
        jdbc.update("UPDATE ont_object SET deleted = TRUE, applied_revision = ?, updated_at = now() "
                        + "WHERE global_id = ? AND ? >= applied_revision",
                revision, globalId, revision);
    }

    public void truncateProjection() {
        // 只清 4 张当前投影表；审计历史 8 张与模型/配置一律不动（Q2 / §10.4 第 7 条）。
        jdbc.execute("TRUNCATE TABLE ont_object, ont_relation, ont_source_ref, ont_object_version");
    }

    public ProjectionRow findObject(String globalId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ont_object WHERE global_id = ?", globalId);
        return rows.isEmpty() ? null : toObject(rows.get(0));
    }

    public long appliedRevision(String globalId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT applied_revision FROM ont_object WHERE global_id = ?", Long.class, globalId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    public List<ProjectionRow> allObjects() {
        List<ProjectionRow> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM ont_object ORDER BY object_type, source_pk")) {
            result.add(toObject(row));
        }
        return result;
    }

    public List<RelationInstance> allRelations() {
        return queryRelations("SELECT * FROM ont_relation ORDER BY relation_type, from_global_id");
    }

    public List<RelationInstance> relationsFrom(String globalId, boolean includeInactive) {
        String sql = "SELECT * FROM ont_relation WHERE from_global_id = ?"
                + (includeInactive ? "" : " AND active = TRUE")
                + " ORDER BY relation_type, to_global_id";
        return queryRelations(sql, globalId);
    }

    public List<RelationInstance> relationsTo(String globalId, boolean includeInactive) {
        String sql = "SELECT * FROM ont_relation WHERE to_global_id = ?"
                + (includeInactive ? "" : " AND active = TRUE")
                + " ORDER BY relation_type, from_global_id";
        return queryRelations(sql, globalId);
    }

    public List<SourceRefRow> sourceRefsOf(String globalId) {
        List<SourceRefRow> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM ont_source_ref WHERE global_id = ?", globalId)) {
            result.add(new SourceRefRow(str(row.get("global_id")), str(row.get("source_id")),
                    str(row.get("source_table")), str(row.get("source_pk")),
                    str(row.get("source_code")), str(row.get("source_hash")),
                    str(row.get("mapping_version")), lng(row.get("last_seen_revision"))));
        }
        return result;
    }

    public long countObjects() {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM ont_object WHERE deleted = FALSE", Long.class);
        return value == null ? 0L : value;
    }

    public long countRelations() {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM ont_relation WHERE active = TRUE", Long.class);
        return value == null ? 0L : value;
    }

    public long changeHistoryCount() {
        Long value = jdbc.queryForObject("SELECT count(*) FROM ont_change_history", Long.class);
        return value == null ? 0L : value;
    }

    public long syncTaskCount() {
        Long value = jdbc.queryForObject("SELECT count(*) FROM ont_sync_task", Long.class);
        return value == null ? 0L : value;
    }

    public long errorLogCount() {
        Long value = jdbc.queryForObject("SELECT count(*) FROM ont_error_log", Long.class);
        return value == null ? 0L : value;
    }

    // ── 运行记录 ────────────────────────────────────────────────────────────

    public long appendSyncTask(String taskType, String status, long snapshotRevision,
                               Map<String, Object> progress) {
        Long id = jdbc.queryForObject("""
                INSERT INTO ont_sync_task
                  (task_type, status, snapshot_revision, end_revision, progress_json, started_at)
                VALUES (?, ?, ?, 0, ?::jsonb, now())
                RETURNING task_id
                """, Long.class, taskType, status, snapshotRevision, json(progress));
        return id == null ? -1L : id;
    }

    public void updateSyncTask(long taskId, String status, long endRevision,
                               Map<String, Object> progress, String error) {
        jdbc.update("UPDATE ont_sync_task SET status = ?, end_revision = ?, progress_json = ?::jsonb, "
                        + "error = ?, finished_at = now() WHERE task_id = ?",
                status, endRevision, json(progress), error, taskId);
    }

    public Map<String, Object> syncTask(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ont_sync_task WHERE task_id = ?", taskId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> syncTasks(int limit, int offset) {
        return jdbc.queryForList("SELECT * FROM ont_sync_task ORDER BY task_id DESC LIMIT ? OFFSET ?",
                limit, offset);
    }

    public boolean hasRunningTask(String taskType) {
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM ont_sync_task WHERE task_type = ? AND status = 'RUNNING'",
                Integer.class, taskType);
        return value != null && value > 0;
    }

    public long appendReconcileRun(String status, List<Map<String, Object>> diffs,
                                   Map<String, Object> summary) {
        Long id = jdbc.queryForObject("""
                INSERT INTO ont_reconcile_run (status, diff_json, summary_json, started_at)
                VALUES (?, ?::jsonb, ?::jsonb, now())
                RETURNING run_id
                """, Long.class, status, json(diffs), json(summary));
        return id == null ? -1L : id;
    }

    public void updateReconcileRun(long runId, String status, List<Map<String, Object>> diffs,
                                   Map<String, Object> summary) {
        jdbc.update("UPDATE ont_reconcile_run SET status = ?, diff_json = ?::jsonb, "
                        + "summary_json = ?::jsonb, finished_at = now() WHERE run_id = ?",
                status, json(diffs), json(summary), runId);
    }

    public List<Map<String, Object>> reconcileRuns(int limit, int offset) {
        return jdbc.queryForList("SELECT * FROM ont_reconcile_run ORDER BY run_id DESC LIMIT ? OFFSET ?",
                limit, offset);
    }

    public void appendErrorLog(String phase, String refId, String message) {
        jdbc.update("INSERT INTO ont_error_log (event_id, phase, message, created_at) "
                + "VALUES (?, ?, ?, now())", refId, phase, message);
    }

    // ── 数据源状态与模型版本 ────────────────────────────────────────────────

    public void ensureDataSource(String sourceId, String displayName) {
        jdbc.update("INSERT INTO ont_data_source (source_id, display_name) VALUES (?, ?) "
                        + "ON CONFLICT (source_id) DO UPDATE SET display_name = EXCLUDED.display_name",
                sourceId, displayName);
    }

    public void updateDataSource(String sourceId, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return;
        }
        Map<String, String> columns = Map.ofEntries(
                Map.entry("sourceUp", "source_up"),
                Map.entry("status", "status"),
                Map.entry("lastSuccessSyncAt", "last_success_sync_at"),
                Map.entry("snapshotRevision", "snapshot_revision"),
                Map.entry("currentSourceRevision", "current_source_revision"),
                Map.entry("demoTime", "demo_time"),
                Map.entry("demoRunning", "demo_running"),
                Map.entry("anchorRealAt", "anchor_real_at"),
                Map.entry("pendingCount", "pending_count"),
                Map.entry("processedCount", "processed_count"),
                Map.entry("errorCount", "error_count"),
                Map.entry("deadCount", "dead_count"),
                Map.entry("mappingVersion", "mapping_version"));
        StringBuilder sql = new StringBuilder("UPDATE ont_data_source SET ");
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String column = columns.get(entry.getKey());
            if (column == null) {
                continue;
            }
            sql.append(safeIdentifier(column)).append(" = ?, ");
            args.add(entry.getValue());
        }
        if (args.isEmpty()) {
            return;
        }
        sql.append("updated_at = now() WHERE source_id = ?");
        args.add(sourceId);
        jdbc.update(sql.toString(), args.toArray());
    }

    public Map<String, Object> dataSource(String sourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ont_data_source WHERE source_id = ?", sourceId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public void upsertMappingVersion(String sourceId, String mappingVersion, String definitionHash,
                                     String status) {
        jdbc.update("INSERT INTO ont_mapping_version "
                        + "(source_id, mapping_version, definition_hash, activated_at, status) "
                        + "VALUES (?, ?, ?, now(), ?) "
                        + "ON CONFLICT (source_id, mapping_version) DO UPDATE SET status = EXCLUDED.status",
                sourceId, mappingVersion, definitionHash, status);
    }

    public void upsertModelDef(String defKey, String defType, Object content, String mappingVersion) {
        jdbc.update("INSERT INTO ont_model_def (def_key, def_type, content, mapping_version, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, ?, now()) "
                        + "ON CONFLICT (def_key) DO UPDATE SET content = EXCLUDED.content, "
                        + "mapping_version = EXCLUDED.mapping_version, updated_at = now()",
                defKey, defType, json(content), mappingVersion);
    }

    public List<Map<String, Object>> mappingVersions(String sourceId) {
        return jdbc.queryForList("SELECT * FROM ont_mapping_version WHERE source_id = ? "
                + "ORDER BY activated_at DESC", sourceId);
    }

    // ── 查询侧 ──────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listObjects(String objectType, List<String> searchFields,
                                                 String q, Map<String, String> filters,
                                                 int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ont_object WHERE deleted = FALSE");
        List<Object> args = new ArrayList<>();
        if (objectType != null && !objectType.isBlank()) {
            sql.append(" AND object_type = ?");
            args.add(objectType);
        }
        appendSearchClause(sql, args, searchFields, q);
        appendFilterClause(sql, args, filters);
        sql.append(" ORDER BY object_type, source_pk LIMIT ? OFFSET ?");
        args.add(size);
        args.add(Math.max(page - 1, 0) * size);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long countObjects(String objectType, List<String> searchFields, String q,
                             Map<String, String> filters) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ont_object WHERE deleted = FALSE");
        List<Object> args = new ArrayList<>();
        if (objectType != null && !objectType.isBlank()) {
            sql.append(" AND object_type = ?");
            args.add(objectType);
        }
        appendSearchClause(sql, args, searchFields, q);
        appendFilterClause(sql, args, filters);
        Long value = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    /** 全局直达搜索：按各类型定义的可搜索字段跨全部已接入类型命中（US-01 / Q4）。 */
    public List<Map<String, Object>> search(String q, Map<String, List<String>> searchableByType,
                                            int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM ont_object WHERE deleted = FALSE AND (");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : searchableByType.entrySet()) {
            for (String field : entry.getValue()) {
                if (!first) {
                    sql.append(" OR ");
                }
                sql.append("(object_type = ? AND attributes->>'")
                        .append(safeJsonKey(field)).append("' ILIKE ?)");
                args.add(entry.getKey());
                args.add("%" + q + "%");
                first = false;
            }
        }
        if (first) {
            return List.of();
        }
        sql.append(") ORDER BY object_type, source_pk LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> changes(String globalId, String role, int limit, int offset) {
        if (globalId == null || globalId.isBlank()) {
            return jdbc.queryForList("SELECT * FROM ont_change_history "
                    + "ORDER BY change_id DESC LIMIT ? OFFSET ?", limit, offset);
        }
        if (role == null || role.isBlank()) {
            return jdbc.queryForList("SELECT h.* FROM ont_change_history h "
                            + "WHERE h.global_id = ? OR EXISTS (SELECT 1 FROM ont_change_impact i "
                            + "WHERE i.change_id = h.change_id AND i.global_id = ?) "
                            + "ORDER BY h.change_id DESC LIMIT ? OFFSET ?",
                    globalId, globalId, limit, offset);
        }
        return jdbc.queryForList("SELECT h.* FROM ont_change_history h "
                        + "JOIN ont_change_impact i ON i.change_id = h.change_id "
                        + "WHERE i.global_id = ? AND i.role = ? "
                        + "ORDER BY h.change_id DESC LIMIT ? OFFSET ?",
                globalId, role, limit, offset);
    }

    public long countChanges(String globalId, String role) {
        if (globalId == null || globalId.isBlank()) {
            Long value = jdbc.queryForObject("SELECT count(*) FROM ont_change_history", Long.class);
            return value == null ? 0L : value;
        }
        if (role == null || role.isBlank()) {
            Long value = jdbc.queryForObject("SELECT count(*) FROM ont_change_history h "
                            + "WHERE h.global_id = ? OR EXISTS (SELECT 1 FROM ont_change_impact i "
                            + "WHERE i.change_id = h.change_id AND i.global_id = ?)",
                    Long.class, globalId, globalId);
            return value == null ? 0L : value;
        }
        Long value = jdbc.queryForObject("SELECT count(*) FROM ont_change_impact "
                        + "WHERE global_id = ? AND role = ?",
                Long.class, globalId, role);
        return value == null ? 0L : value;
    }

    public Map<String, Object> changeById(long changeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ont_change_history WHERE change_id = ?", changeId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> impactsOf(long changeId) {
        return jdbc.queryForList("SELECT * FROM ont_change_impact WHERE change_id = ? "
                + "ORDER BY global_id", changeId);
    }

    public long countImpactByRole(String role) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM ont_change_impact WHERE role = ?", Long.class, role);
        return value == null ? 0L : value;
    }

    public List<Map<String, Object>> recentChanges(int limit) {
        return jdbc.queryForList("SELECT * FROM ont_change_history "
                + "ORDER BY change_id DESC LIMIT ?", limit);
    }

    public Map<String, Object> objectRow(String globalId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ont_object WHERE global_id = ?", globalId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private void appendSearchClause(StringBuilder sql, List<Object> args,
                                    List<String> fields, String q) {
        if (q == null || q.isBlank() || fields == null || fields.isEmpty()) {
            return;
        }
        sql.append(" AND (");
        boolean first = true;
        for (String field : fields) {
            if (!first) {
                sql.append(" OR ");
            }
            sql.append("attributes->>'").append(safeJsonKey(field)).append("' ILIKE ?");
            args.add("%" + q + "%");
            first = false;
        }
        sql.append(")");
    }

    private void appendFilterClause(StringBuilder sql, List<Object> args, Map<String, String> filters) {
        if (filters == null) {
            return;
        }
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            sql.append(" AND attributes->>'").append(safeJsonKey(entry.getKey())).append("' = ?");
            args.add(entry.getValue());
        }
    }

    private List<RelationInstance> queryRelations(String sql, Object... args) {
        List<RelationInstance> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            result.add(new RelationInstance(
                    str(row.get("relation_id")), str(row.get("relation_type")),
                    str(row.get("from_global_id")), str(row.get("to_global_id")),
                    str(row.get("reason")), str(row.get("source_table")),
                    str(row.get("source_pk")), str(row.get("source_hash")),
                    str(row.get("mapping_version")), dateTime(row.get("valid_from")),
                    dateTime(row.get("valid_to")), bool(row.get("active")),
                    lng(row.get("applied_revision"))));
        }
        return result;
    }

    private ProjectionRow toObject(Map<String, Object> row) {
        return new ProjectionRow(str(row.get("global_id")), str(row.get("source_id")),
                str(row.get("object_type")), str(row.get("source_pk")),
                str(row.get("title")), parseMap(row.get("attributes")),
                str(row.get("mapping_version")), lng(row.get("applied_revision")),
                bool(row.get("deleted")));
    }

    public static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static long lng(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public static boolean bool(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "t".equalsIgnoreCase(String.valueOf(value));
    }

    public static LocalDateTime dateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        try {
            return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T').substring(0, 19));
        } catch (Exception e) {
            return null;
        }
    }

    public static Set<String> safeJsonKeys(List<String> fields) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String field : fields) {
            result.add(safeJsonKey(field));
        }
        return result;
    }
}
