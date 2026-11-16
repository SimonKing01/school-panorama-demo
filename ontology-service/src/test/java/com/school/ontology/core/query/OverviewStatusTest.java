package com.school.ontology.core.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷回归：「快照水位 85 < 来源 87」却显示为「已同步」。
 *
 * <p>五态判定必须把「水位落后」算进去，且状态与 hint 都要能被前端直接展示。</p>
 */
class OverviewStatusTest {

    private static final String LAST_SYNC = "2026-11-15T20:06:49";

    @Test
    void 水位落后且无错误无待处理时判定为同步落后() {
        String status = OverviewService.resolveStatus(true, 0L, 0L, 0L, 0L, 85L, 87L,
                LAST_SYNC, 30L);
        assertEquals("LAGGING", status, "snapshot 85 < current 87 不能算已同步");

        String hint = OverviewService.hintOf(status, true, 85L, 87L);
        assertTrue(hint.contains("85") && hint.contains("87"),
                "落后提示要带真实水位数字，实际：" + hint);
    }

    @Test
    void 水位追平后判定为已同步() {
        assertEquals("SYNCED", OverviewService.resolveStatus(true, 0L, 0L, 0L, 0L, 87L, 87L,
                LAST_SYNC, 30L));
    }

    @Test
    void 从未成功导入仍判为尚未接入而不是落后() {
        // 新库 snapshot=0、来源 revision 必然更大：若先判落后会永远显示「同步落后」，
        // 把更可操作的「尚未接入」掩盖掉。
        assertEquals("NOT_CONNECTED", OverviewService.resolveStatus(true, 0L, 0L, 0L, 0L,
                0L, 87L, null, 0L));
    }

    @Test
    void 失败与来源不可用与待处理优先于水位比较() {
        assertEquals("FAILED", OverviewService.resolveStatus(true, 0L, 1L, 0L, 0L, 85L, 87L,
                LAST_SYNC, 30L), "存在死信即失败");
        assertEquals("FAILED", OverviewService.resolveStatus(true, 0L, 0L, 0L, 2L, 85L, 87L,
                LAST_SYNC, 30L), "存在错误记录即失败");
        assertEquals("LAGGING", OverviewService.resolveStatus(false, 0L, 0L, 0L, 0L, 87L, 87L,
                LAST_SYNC, 30L), "来源不可用虽可查，但不算新鲜");
        assertEquals("LAGGING", OverviewService.resolveStatus(true, 3L, 0L, 0L, 0L, 87L, 87L,
                LAST_SYNC, 30L), "有积压事件即落后");
    }

    @Test
    void 已同步但对象数为零判定为没有业务数据() {
        assertEquals("NO_DATA", OverviewService.resolveStatus(true, 0L, 0L, 0L, 0L, 87L, 87L,
                LAST_SYNC, 0L));
    }
}
