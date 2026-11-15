package com.school.ontology.core.spi;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 演示业务时间（demoTime）：业务事实的一部分，可设置/回拨/跟随真实时间推进。
 * 与同步处理时间严格分离：本对象只用于「当前课程/下一节课」等动态计算。
 */
public record BusinessTime(LocalDateTime demoAt, boolean running, LocalDateTime anchorRealAt) {

    /** 按与原业务 DemoClockService.now() 相同的语义推进：running 时 demoAt + (真实now - anchor)。 */
    public LocalDateTime at(LocalDateTime realNow) {
        if (demoAt == null) {
            return realNow;
        }
        if (!running || anchorRealAt == null) {
            return demoAt;
        }
        return demoAt.plus(Duration.between(anchorRealAt, realNow));
    }
}
