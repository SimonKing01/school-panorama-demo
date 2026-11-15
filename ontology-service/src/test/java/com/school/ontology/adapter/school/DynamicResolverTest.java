package com.school.ontology.adapter.school;

import com.school.ontology.adapter.school.resolver.LessonStatusResolver;
import com.school.ontology.adapter.school.resolver.SchoolLessonResolver;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.spi.DynamicResult;
import com.school.ontology.core.spi.ResolveContext;
import com.school.ontology.support.StubQueryPort;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C2/C3/F6：动态结果随演示时间变化、无写入也变化，且必须带可读计算依据。 */
class DynamicResolverTest {

    private static final String CLASS_GID = TestFixtures.gid("class", "1");
    private static final String SESSION_1 = TestFixtures.gid("lessonSession", "1");
    private static final String SESSION_2 = TestFixtures.gid("lessonSession", "2");

    private StubQueryPort query() {
        ProjectionRow first = TestFixtures.object("lessonSession", "1", 84, lesson(
                "LS-001", "2026-11-14", "10:00", "10:45"));
        ProjectionRow second = TestFixtures.object("lessonSession", "2", 84, lesson(
                "LS-002", "2026-11-14", "10:55", "11:40"));
        return new StubQueryPort()
                .addObject(first).addObject(second)
                .addRelationTo(CLASS_GID, TestFixtures.relation("session_of_class",
                        SESSION_1, CLASS_GID, 84))
                .addRelationTo(CLASS_GID, TestFixtures.relation("session_of_class",
                        SESSION_2, CLASS_GID, 84));
    }

    private static Map<String, Object> lesson(String code, String date,
                                              String start, String end) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("code", code);
        attributes.put("lessonDate", date);
        attributes.put("startTime", start);
        attributes.put("endTime", end);
        attributes.put("courseName", "语文");
        return attributes;
    }

    private static ResolveContext ctx(String key, String globalId, LocalDateTime time,
                                      Map<String, Object> attributes, StubQueryPort query) {
        return new ResolveContext(key, globalId, "class", time, attributes, query);
    }

    @Test
    void 当前课与下一节课随演示时间变化且附计算依据() {
        SchoolLessonResolver resolver = new SchoolLessonResolver();
        StubQueryPort query = query();
        assertTrue(resolver.aliases().contains("nextLesson"));

        LocalDateTime at1015 = LocalDateTime.of(2026, 11, 14, 10, 15);
        DynamicResult current1015 = resolver.resolve(
                ctx("currentLesson", CLASS_GID, at1015, Map.of(), query));
        assertNotNull(current1015.value(), "10:15 应命中 10:00-10:45 的当前课");
        assertTrue(current1015.basis().contains("演示时间"));
        assertTrue(current1015.basis().contains("开始≤演示时间<结束"));

        DynamicResult next1015 = resolver.resolve(
                ctx("nextLesson", CLASS_GID, at1015, Map.of(), query));
        assertNotNull(next1015.value(), "10:15 应有下一节课");
        assertEquals("LS-002", ((Map<?, ?>) next1015.value()).get("code"));

        // 无任何数据库写入，仅把演示时间推进到 10:45，当前课自然变为空状态。
        LocalDateTime at1045 = LocalDateTime.of(2026, 11, 14, 10, 45);
        DynamicResult current1045 = resolver.resolve(
                ctx("currentLesson", CLASS_GID, at1045, Map.of(), query));
        assertNull(current1045.value(), "10:45 起无满足 开始≤时间<结束 的安排，显示空状态");

        LocalDateTime at1700 = LocalDateTime.of(2026, 11, 14, 17, 0);
        DynamicResult next1700 = resolver.resolve(
                ctx("nextLesson", CLASS_GID, at1700, Map.of(), query));
        assertNull(next1700.value(), "17:00 后无下一节课，显示空状态");
    }

    @Test
    void 授课状态按查询时间计算并附依据() {
        LessonStatusResolver resolver = new LessonStatusResolver();
        Map<String, Object> attributes = lesson("LS-001", "2026-11-14", "10:00", "10:45");
        DynamicResult during = resolver.resolve(ctx("lessonStatusResolver", SESSION_1,
                LocalDateTime.of(2026, 11, 14, 10, 15), attributes, query()));
        assertEquals("进行中", during.value());
        assertTrue(during.basis().contains("演示时间"));

        DynamicResult finished = resolver.resolve(ctx("lessonStatusResolver", SESSION_1,
                LocalDateTime.of(2026, 11, 14, 17, 0), attributes, query()));
        assertEquals("已结束", finished.value());
        assertEquals(List.of("lessonDate", "startTime", "endTime", "queryTime"),
                finished.inputs());
    }
}
