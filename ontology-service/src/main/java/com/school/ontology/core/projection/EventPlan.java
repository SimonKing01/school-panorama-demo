package com.school.ontology.core.projection;

import java.util.List;
import java.util.Map;

/**
 * 一次事件的「投影变更计划」，由 EventSnapshotResolver 依据事件负载与只读业务库生成。
 * 把「读来源」与「写本体」分离，使 EventConsumer 可在无数据库环境下被单元测试验证。
 *
 * <p>三类批次必须分开：</p>
 * <ul>
 *   <li>{@code batches} 是事件**自身**对象的批次，允许按定义重算全部关系（外键 + 派生）。</li>
 *   <li>{@code refreshBatches} 是「相关对象」的属性刷新批次，只允许写对象属性与来源引用，
 *       绝不能重算关系——否则它们会因为没有 relations() 而被当成「所有出边都该结束」，
 *       把班级班主任、学生所在班级这类根本没变的关系误停（阻断级回归，见批次④ 缺陷记录）。</li>
 *   <li>{@code derivedRefreshBatches} 是「相关对象」的**派生关系**重算批次，只带
 *       {@code generation.kind == JOIN_RULE} 的关系。它是一条独立通道：落库时只按派生关系类型
 *       做「不在保留清单即结束」，外键关系零影响。存在的理由见 P8——
 *       调课换教师后课的 4 条外键关系会立刻换边，但 8 名学生的
 *       {@code student_taught_by} 是联表算出来的，必须跟着重算，否则同一业务事实
 *       在关系图 / 学生详情 / 关系列表三个视角下不一致。</li>
 * </ul>
 *
 * <p>{@code changeUnits} 是逐条目变更单元：批量写入口一次提交多条业务变更却只发 1 条事件，
 * 单元数 &gt; 1 时变更历史按条目拆成多条，每条只带自己的 before/after/diff；
 * 单元数 ≤ 1 时（单条 ADJUST / DELETE / 全量提示 / 人工注入）完全沿用原有整批逻辑。</p>
 */
public record EventPlan(List<SnapshotBatch> batches, List<String> tombstoneGlobalIds,
                        Map<String, Object> demoTimeUpdate, List<String> selfGlobalIds,
                        List<String> relatedGlobalIds, Map<String, Object> changeInfo,
                        List<SnapshotBatch> refreshBatches, List<ChangeUnit> changeUnits,
                        List<SnapshotBatch> derivedRefreshBatches) {

    /**
     * 兼容构造：不涉及派生关系重算的场景（CLOCK / 全量重扫提示 / 墓碑 / 人工注入事件 / 单测）
     * 不必显式给出空表。显式保留这个 8 参重载，是为了让「派生关系重算」成为一处**显式新增**，
     * 而不是悄悄改变既有调用点的语义。
     */
    public EventPlan(List<SnapshotBatch> batches, List<String> tombstoneGlobalIds,
                     Map<String, Object> demoTimeUpdate, List<String> selfGlobalIds,
                     List<String> relatedGlobalIds, Map<String, Object> changeInfo,
                     List<SnapshotBatch> refreshBatches, List<ChangeUnit> changeUnits) {
        this(batches, tombstoneGlobalIds, demoTimeUpdate, selfGlobalIds, relatedGlobalIds,
                changeInfo, refreshBatches, changeUnits, List.of());
    }

    public static EventPlan empty() {
        return new EventPlan(List.of(), List.of(), Map.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of(), List.of());
    }
}
