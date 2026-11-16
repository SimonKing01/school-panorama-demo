package com.school.ontology.core.projection;

import java.util.List;
import java.util.Map;

/**
 * 多条目事件里的「一个条目的变更单元」。
 *
 * <p>批量写入口（如一次调 2 条课的 {@code BATCH_ADJUST}）只发 1 条 Outbox 事件，
 * 负载里 {@code items[]} 是逐条业务变更，而顶层 {@code diff} 是所有条目合并后的
 * {@code allDiffs}。如果仍按「一个事件一条变更历史」落库，那条记录会同时带两个对象的
 * 字段变化，挂到第一个对象名下——点开 LS-001 的「近期变化」能看到只属于 LS-002 的
 * 「李静怡 → 周文博」，与业务侧 {@code change_records}（每条约只有自己的 diff）不一致。</p>
 *
 * <p>因此事件计划里携带逐条目的变更单元，{@code ProjectionWriter} 在单元数 &gt; 1 时
 * 按条目拆成多条变更历史，每条只描述自己的对象。</p>
 *
 * @param selfGlobalId 本条目对应的对象全局 ID（变更历史的锚点）
 * @param before       条目级「变更前」字段快照
 * @param after        条目级「变更后」字段快照
 * @param diff         条目级字段差集（不含其他条目的字段）
 * @param sourceTable  来源表，便于追溯
 * @param sourcePk     来源主键
 * @param reason       条目级原因（为空时回退事件级 reason）
 */
public record ChangeUnit(String selfGlobalId, Map<String, Object> before, Map<String, Object> after,
                         List<Map<String, Object>> diff, String sourceTable, String sourcePk,
                         String reason) {
}
