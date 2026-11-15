package com.school.ontology.core.query;

import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.spi.DynamicResolver;
import com.school.ontology.core.spi.DynamicResult;
import com.school.ontology.core.spi.QueryPort;
import com.school.ontology.core.spi.ResolveContext;
import com.school.ontology.core.spi.SpiRegistry;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态计算的唯一调用点：详情 / 列表 / 搜索 / 总览都经此分派。
 * 调用范围来自模型定义的 dynamicResolvers 与 derivedProperties.resolver，
 * 内核按 key 查策略表，不认识「学校/课程」，也不重复实现算法。
 */
@Component
public class DynamicResolverDispatcher {

    private final SpiRegistry spi;
    private final QueryPort query;
    private final ModelRegistry registry;

    public DynamicResolverDispatcher(SpiRegistry spi, QueryPort query, ModelRegistry registry) {
        this.spi = spi;
        this.query = query;
        this.registry = registry;
    }

    public List<DynamicResult> resolveAll(String globalId, String objectType,
                                          LocalDateTime queryTime) {
        ObjectTypeDef def = registry.typeOf(objectType);
        Set<String> keys = new LinkedHashSet<>(def.dynamicResolvers);
        for (DerivedPropertyDef derived : def.derivedProperties) {
            if (derived != null && derived.resolver != null && !derived.resolver.isBlank()) {
                keys.add(derived.resolver);
            }
        }
        Map<String, Object> attributes = query.attributes(globalId);
        List<DynamicResult> results = new ArrayList<>();
        for (String key : keys) {
            DynamicResolver resolver = spi.resolver(key);
            if (resolver == null) {
                continue;
            }
            if (!resolver.supportedTypes().isEmpty()
                    && !resolver.supportedTypes().contains(objectType)) {
                continue;
            }
            ResolveContext ctx = new ResolveContext(key, globalId, objectType, queryTime,
                    attributes, query);
            results.add(resolver.resolve(ctx));
        }
        return results;
    }
}
