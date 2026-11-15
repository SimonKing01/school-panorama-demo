package com.school.ontology.support;

import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.sync.EventSnapshotResolver;
import com.school.ontology.core.spi.OutboxEvent;

/** 可配置的事件计划解析器：让消费逻辑在完全无数据库的环境里可验证。 */
public class StubEventSnapshotResolver implements EventSnapshotResolver {

    private EventPlan plan;
    private OutboxEvent lastEvent;

    public StubEventSnapshotResolver(EventPlan plan) {
        this.plan = plan;
    }

    @Override
    public EventPlan plan(OutboxEvent event) {
        this.lastEvent = event;
        return plan;
    }

    public void setPlan(EventPlan plan) {
        this.plan = plan;
    }

    public OutboxEvent lastEvent() {
        return lastEvent;
    }
}
