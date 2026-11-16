package com.school.ontology.core.projection;

import java.util.List;
import java.util.Map;

/**
 * 一次事件的「投影变更计划」，由 EventSnapshotResolver 依据事件负载与只读业务库生成。
 * 把「读来源」与「写本体」分离，使 EventConsumer 可在无数据库环境下被单元测试验证。
 *
 * <p>两类批次必须分开：{@code batches} 是事件**自身**对象的批次，允许按定义重算关系；
 * {@code refreshBatches} 是「相关对象」的刷新批次，只允许写对象属性与来源引用，
 * 绝不能重算关系——否则它们会因为没有 relations() 而被当成「所有出边都该结束」，
 * 把班级班主任、学生所在班级这类根本没变的关系误停（阻断级回归，见批次④ 缺陷记录）。</p>
 *
 * <p>{@code changeUnits} 是逐条目变更单元：批量写入口一次提交多条业务变更却只发 1 条事件，
 * 单元数 &gt; 1 时变更历史按条目拆成多条，每条只带自己的 before/after/diff；
 * 单元数 ≤ 1 时（单条 ADJUST / DELETE / 全量提示 / 人工注入）完全沿用原有整批逻辑。</p>
 */
public record EventPlan(List<SnapshotBatch> batches, List<String> tombstoneGlobalIds,
                        Map<String, Object> demoTimeUpdate, List<String> selfGlobalIds,
                        List<String> relatedGlobalIds, Map<String, Object> changeInfo,
                        List<SnapshotBatch> refreshBatches, List<ChangeUnit> changeUnits) {

    public static EventPlan empty() {
        return new EventPlan(List.of(), List.of(), Map.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of());
    }
}
