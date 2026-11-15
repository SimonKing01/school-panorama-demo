package com.school.ontology.core.projection;

import java.util.List;
import java.util.Map;

/**
 * 一次事件的「投影变更计划」，由 EventSnapshotResolver 依据事件负载与只读业务库生成。
 * 把「读来源」与「写本体」分离，使 EventConsumer 可在无数据库环境下被单元测试验证。
 */
public record EventPlan(List<SnapshotBatch> batches, List<String> tombstoneGlobalIds,
                        Map<String, Object> demoTimeUpdate, List<String> selfGlobalIds,
                        List<String> relatedGlobalIds, Map<String, Object> changeInfo) {

    public static EventPlan empty() {
        return new EventPlan(List.of(), List.of(), Map.of(), List.of(), List.of(), Map.of());
    }
}
