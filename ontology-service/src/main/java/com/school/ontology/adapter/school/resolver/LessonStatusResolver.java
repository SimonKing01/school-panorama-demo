package com.school.ontology.adapter.school.resolver;

import com.school.ontology.common.Resp;
import com.school.ontology.core.spi.DynamicResolver;
import com.school.ontology.core.spi.DynamicResult;
import com.school.ontology.core.spi.ResolveContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 授课安排状态（按查询时间）：未开始 / 进行中 / 已结束。
 * 与 currentLesson、nextLesson 共用同一时间源与同一套展示依据，不重复实现。
 */
@Component
public class LessonStatusResolver implements DynamicResolver {

    @Override
    public String key() {
        return "lessonStatusResolver";
    }

    @Override
    public DynamicResult resolve(ResolveContext ctx) {
        LocalDateTime queryTime = ctx.queryTime() == null ? LocalDateTime.now() : ctx.queryTime();
        Map<String, Object> attributes = ctx.attributes();
        String basis;
        String value;
        try {
            LocalDate date = LocalDate.parse(String.valueOf(attributes.get("lessonDate")));
            LocalTime start = LocalTime.parse(String.valueOf(attributes.get("startTime")));
            LocalTime end = LocalTime.parse(String.valueOf(attributes.get("endTime")));
            LocalDateTime startAt = LocalDateTime.of(date, start);
            LocalDateTime endAt = LocalDateTime.of(date, end);
            if (!startAt.isAfter(queryTime) && endAt.isAfter(queryTime)) {
                value = "进行中";
            } else if (startAt.isAfter(queryTime)) {
                value = "未开始";
            } else {
                value = "已结束";
            }
            basis = "依据演示时间 " + Resp.ts(queryTime) + " 与来源属性 上课日期 "
                    + Resp.d(date) + "、开始 " + Resp.hm(start) + "、结束 " + Resp.hm(end)
                    + " 比较：开始≤演示时间<结束为进行中，开始晚于演示时间为未开始，其余为已结束。";
        } catch (Exception e) {
            value = "未知";
            basis = "依据演示时间 " + Resp.ts(queryTime)
                    + "，对象缺少可解析的上课日期或起止时间，无法判定状态（显示空状态）。";
        }
        return new DynamicResult(ctx.resolverKey(), value, basis,
                List.of("lessonDate", "startTime", "endTime", "queryTime"), queryTime);
    }
}
