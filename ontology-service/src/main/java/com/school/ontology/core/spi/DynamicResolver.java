package com.school.ontology.core.spi;

import java.util.List;

/**
 * 依赖查询时间的动态计算（§5）。每次调用基于 ctx.queryTime 实时计算，
 * 结果不落库、不缓存，且必须带可读计算依据 basis。
 */
public interface DynamicResolver {

    String key();

    /** 同一实现可服务多个 key（如 currentLesson / nextLesson），仍是数据而非分支。 */
    default List<String> aliases() {
        return List.of();
    }

    /** 声明支持的对象类型；空列表表示不限类型，由定义中的 dynamicResolvers 决定调用范围。 */
    default List<String> supportedTypes() {
        return List.of();
    }

    DynamicResult resolve(ResolveContext ctx);
}
