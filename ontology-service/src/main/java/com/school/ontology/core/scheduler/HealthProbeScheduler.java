package com.school.ontology.core.scheduler;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.spi.BusinessTime;
import com.school.ontology.core.spi.SourceAdapter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 来源健康与演示业务时间探测（E6 / F6）。
 *
 * <p>把 sourceUp 标记与 demo_at/running/anchor 写入 ont_data_source：
 * 原业务停止后仍可查询已同步数据，并显示「来源不可用 + 最后成功同步时间」；
 * 动态计算使用 demoTime 而非真实处理时间，即使 CLOCK 事件缺失也能随时钟推进。</p>
 */
@Component
public class HealthProbeScheduler {

    private final SourceAdapter adapter;
    private final ProjectionStore store;
    private final ModelRegistry registry;

    public HealthProbeScheduler(SourceAdapter adapter, ProjectionStore store,
                                ModelRegistry registry) {
        this.adapter = adapter;
        this.store = store;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${ontology.sync.health-ms:5000}")
    public void run() {
        Map<String, Object> update = new LinkedHashMap<>();
        try {
            boolean up = adapter.healthCheck();
            BusinessTime businessTime = adapter.currentBusinessTime();
            update.put("sourceUp", up);
            update.put("demoTime", businessTime.demoAt());
            update.put("demoRunning", businessTime.running());
            update.put("anchorRealAt", businessTime.anchorRealAt());
            if (up) {
                update.put("currentSourceRevision", adapter.currentWatermark());
            }
            // 注意：不写 lastSuccessSyncAt（那是真实处理时间，只有成功消费才更新）。
            store.updateDataSource(registry.sourceId(), update);
        } catch (Exception e) {
            update.put("sourceUp", false);
            try {
                store.updateDataSource(registry.sourceId(), update);
            } catch (Exception ignored) {
                // 来源与本体库同时不可用时，下一轮再探测。
            }
        }
    }
}
