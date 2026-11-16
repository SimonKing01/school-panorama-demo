package com.school.ontology.core.query;

import com.school.ontology.core.model.AttributeDef;
import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.model.ObjectTypeDef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷回归：详情页「状态(按查询时间)」恒显示「—」。
 *
 * <p>resolver 驱动的派生属性只在查询时计算、不落库，所以落库值恒为空；
 * 必须用同名 resolver key 的动态结果回填。provider 驱动的派生属性仍取落库值，不能被覆盖。</p>
 */
class AttributeViewFallbackTest {

    private static ObjectTypeDef lessonType() {
        ObjectTypeDef def = new ObjectTypeDef();
        def.id = "lessonSession";
        def.displayName = "具体授课安排";

        AttributeDef code = new AttributeDef();
        code.name = "code";
        code.type = "string";
        code.displayName = "安排编号";

        DerivedPropertyDef status = new DerivedPropertyDef();
        status.name = "status";
        status.type = "string";
        status.displayName = "状态(按查询时间)";
        status.resolver = "lessonStatusResolver";

        DerivedPropertyDef studentCount = new DerivedPropertyDef();
        studentCount.name = "studentCount";
        studentCount.type = "int";
        studentCount.displayName = "班级人数";
        studentCount.provider = "classStudentCountProvider";

        def.attributes = List.of(code);
        def.derivedProperties = List.of(status, studentCount);
        return def;
    }

    private static Map<String, Object> storedAttributes() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("code", "LS-003");
        // provider 驱动的派生属性在快照期算好并落库；resolver 驱动的不会出现在这里。
        stored.put("studentCount", 40);
        return stored;
    }

    @Test
    void 状态由动态结果回填而provider派生值保持落库值() {
        List<Map<String, Object>> views = OntologyQueryService.attributeViews(
                lessonType(), storedAttributes(), Map.of("lessonStatusResolver", "未开始"));

        assertEquals(3, views.size(), "1 个来源属性 + 2 个派生属性");
        Map<String, Object> statusView = views.get(1);
        assertEquals("status", statusView.get("name"));
        assertEquals("状态(按查询时间)", statusView.get("displayName"));
        assertEquals("未开始", statusView.get("value"), "resolver 派生值必须由同查询时间的动态结果回填");
        assertEquals("DERIVED", statusView.get("origin"));
        assertEquals("lessonStatusResolver", statusView.get("resolver"));

        Map<String, Object> countView = views.get(2);
        assertEquals("studentCount", countView.get("name"));
        assertEquals(40, countView.get("value"), "provider 派生值取落库值，不被动态结果覆盖");
        assertEquals("classStudentCountProvider", countView.get("provider"));
    }

    @Test
    void 动态结果缺失时保持空值且不抛异常() {
        List<Map<String, Object>> views = OntologyQueryService.attributeViews(
                lessonType(), storedAttributes(), Map.of());

        Map<String, Object> statusView = views.get(1);
        assertTrue(statusView.containsKey("value"), "字段必须存在（前端按字段渲染）");
        assertNull(statusView.get("value"), "算不出结果时保持空值，由前端显示「—」");
    }
}
