package com.school.ontology.core.projection;

import com.school.ontology.core.model.GlobalIds;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B3 全局 ID 隔离、稳定关系 ID、标题模板与「版本水位防覆盖」SQL 落点。 */
class GlobalIdAndUpsertSqlTest {

    @Test
    void 不同来源相同主键产生不同全局ID() {
        String a = GlobalIds.gid("source-a", "student", "1");
        String b = GlobalIds.gid("source-b", "student", "1");
        assertNotEquals(a, b, "禁止跨源按名称或主键合并");
        assertEquals("gid:source-a:student:1", a);
    }

    @Test
    void 关系ID与标题模板稳定可重算() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 14, 10, 0);
        String first = GlobalIds.relationId("session_taught_by", "gid:s:lessonSession:3",
                "gid:s:teacher:5", from);
        String second = GlobalIds.relationId("session_taught_by", "gid:s:lessonSession:3",
                "gid:s:teacher:5", from);
        assertEquals(first, second);
        assertEquals("LS-003 · 语文 · 初一（1）班", GlobalIds.render(
                "{code} · {courseName} · {className}",
                Map.of("code", "LS-003", "courseName", "语文", "className", "初一（1）班")));
    }

    @Test
    void 条件UPSERT与去重SQL确实带版本水位保护() {
        assertTrue(ConditionUpsertSql.hasVersionGuard(ConditionUpsertSql.UPSERT_OBJECT));
        assertTrue(ConditionUpsertSql.hasVersionGuard(ConditionUpsertSql.UPSERT_RELATION));
        assertTrue(ConditionUpsertSql.INSERT_PROCESSED_EVENT.contains("ON CONFLICT (event_id) DO NOTHING"));
        assertTrue(ConditionUpsertSql.UPSERT_RELATION.contains("ON CONFLICT (relation_id)"));
    }
}
