package com.school.ontology.core.spi;

import java.time.LocalDateTime;
import java.util.List;

/** 动态结果：值 + 可读计算依据 + 用到的对象/字段 + 查询时间（C3「展示计算依据」）。 */
public record DynamicResult(String key, Object value, String basis,
                            List<String> inputs, LocalDateTime queryTime) {

    public static DynamicResult empty(String key, String basis, List<String> inputs,
                                      LocalDateTime queryTime) {
        return new DynamicResult(key, null, basis, inputs, queryTime);
    }
}
