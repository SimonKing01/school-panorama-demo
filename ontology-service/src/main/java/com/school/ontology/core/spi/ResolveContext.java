package com.school.ontology.core.spi;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 动态计算上下文。queryTime 是唯一时间源（来自 demoTime 投影或请求 ?queryTime=），
 * 绝不使用真实处理时间，也不依赖数据变更事件（F6）。
 */
public record ResolveContext(String resolverKey, String globalId, String objectType,
                             LocalDateTime queryTime, Map<String, Object> attributes,
                             QueryPort query) {
}
