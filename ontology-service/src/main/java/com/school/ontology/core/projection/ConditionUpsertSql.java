package com.school.ontology.core.projection;

/**
 * 条件 UPSERT SQL（幂等三件套的第三件）。
 *
 * <p>关键点在 {@code WHERE EXCLUDED.applied_revision >= ont_object.applied_revision}：
 * 旧事件/旧快照即使晚到达，也无法覆盖较新的投影状态（§10.4 第 4 条）。
 * 稳定全局 ID 与唯一约束保证重复导入不产生重复对象/关系。</p>
 */
public final class ConditionUpsertSql {

    public static final String UPSERT_OBJECT = """
            INSERT INTO ont_object
              (global_id, source_id, object_type, source_pk, title, attributes,
               mapping_version, applied_revision, deleted, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now(), now())
            ON CONFLICT (source_id, object_type, source_pk) DO UPDATE SET
              title = EXCLUDED.title,
              attributes = EXCLUDED.attributes,
              mapping_version = EXCLUDED.mapping_version,
              applied_revision = EXCLUDED.applied_revision,
              deleted = EXCLUDED.deleted,
              updated_at = now()
            WHERE EXCLUDED.applied_revision >= ont_object.applied_revision
            """;

    public static final String UPSERT_RELATION = """
            INSERT INTO ont_relation
              (relation_id, relation_type, from_global_id, to_global_id, reason,
               source_table, source_pk, source_hash, mapping_version,
               valid_from, valid_to, active, applied_revision)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (relation_id) DO UPDATE SET
              reason = EXCLUDED.reason,
              source_hash = EXCLUDED.source_hash,
              mapping_version = EXCLUDED.mapping_version,
              valid_to = EXCLUDED.valid_to,
              active = EXCLUDED.active,
              applied_revision = EXCLUDED.applied_revision
            WHERE EXCLUDED.applied_revision >= ont_relation.applied_revision
            """;

    public static final String UPSERT_SOURCE_REF = """
            INSERT INTO ont_source_ref
              (global_id, source_id, source_table, source_pk, source_code,
               source_hash, mapping_version, last_seen_revision)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (global_id, source_id) DO UPDATE SET
              source_table = EXCLUDED.source_table,
              source_pk = EXCLUDED.source_pk,
              source_code = EXCLUDED.source_code,
              source_hash = EXCLUDED.source_hash,
              mapping_version = EXCLUDED.mapping_version,
              last_seen_revision = EXCLUDED.last_seen_revision
            """;

    public static final String INSERT_PROCESSED_EVENT = """
            INSERT INTO ont_processed_event (event_id, revision, processed_at)
            VALUES (?, ?, now())
            ON CONFLICT (event_id) DO NOTHING
            """;

    private ConditionUpsertSql() {
    }

    /** 供单元测试断言「版本水位防覆盖」确实落在 SQL 中，而非仅文档声明。 */
    public static boolean hasVersionGuard(String sql) {
        return sql != null && sql.contains("EXCLUDED.applied_revision") && sql.contains(">=");
    }
}
