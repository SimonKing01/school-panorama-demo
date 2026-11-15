package com.school.ontology.core.sync;

import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.spi.OutboxEvent;

/** 事件 → 投影变更计划。分离「读来源」与「写本体」，便于无数据库单元测试。 */
public interface EventSnapshotResolver {

    EventPlan plan(OutboxEvent event);
}
