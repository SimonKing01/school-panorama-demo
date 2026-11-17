package com.school.ontology.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.ontology.core.query.OntologyQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷回归：筛选条件「点了没反应」。
 *
 * <p>根因在服务端：{@code ObjectController.list()} 只声明了 {@code filters} 一个参数，
 * Spring 对未声明的 query 参数静默忽略，于是契约 03 §2.6 允许的「按 filterableFields 平铺」
 * 写法（{@code ?type=student&gender=男}）永远不生效，前端筛什么都没变化。</p>
 *
 * <p>这里用桩替身模拟「按属性等值过滤」的查询服务（不连库），断言三件事：
 * 平铺写法与 {@code filters=<JSON>} 写法结果一致、确实收窄结果、未知键与控制参数不被当筛选条件。</p>
 */
class ObjectControllerFilterTest {

    /** 学生样本规模与演示数据同量级：男 20 条、女 12 条，两种筛选结果数值可区分。 */
    private static final int STUDENT_TOTAL = 32;
    private static final int MALE_TOTAL = 20;
    private static final int FEMALE_TOTAL = 12;

    private ObjectController controller;

    @BeforeEach
    void setUp() {
        OntologyQueryService query = mock(OntologyQueryService.class);
        when(query.filterableFieldsOf("student")).thenReturn(Set.of("schoolId", "classId", "gender"));
        when(query.listObjects(any(), any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> listView(invocation.getArgument(0),
                        invocation.<Map<String, String>>getArgument(2)));
        controller = new ObjectController(query, new ObjectMapper());
    }

    @Test
    void flatFilterEqualsJsonFilterAndNarrowsResult() {
        long all = total(listOf("student", null, null, Map.of()));
        long flat = total(listOf("student", null, null, Map.of("gender", "男")));
        long json = total(listOf("student", null, "{\"gender\":\"男\"}", Map.of()));

        assertEquals(STUDENT_TOTAL, all, "不带筛选应返回全部学生");
        assertEquals(MALE_TOTAL, flat, "平铺写法 ?gender=男 必须真的过滤");
        assertEquals(flat, json, "平铺写法与 filters=<JSON> 写法必须返回同一 total");
        assertTrue(flat < all, "筛选后的 total 必须小于不带筛选的 total");
    }

    @Test
    void unknownAndControlParamsAreIgnored() {
        long all = total(listOf("student", null, null, Map.of()));

        // name 不在该类型的 filterableFields 里：忽略、不报错、不改变结果
        long withUnknownField = total(listOf("student", null, null, Map.of("name", "学生1")));
        assertEquals(all, withUnknownField, "未声明的字段必须被忽略");

        // 控制参数绝不能被当成筛选字段（否则会按 attributes->>'type' 之类筛空）
        long withControl = total(listOf("student", null, null,
                Map.of("type", "student", "page", "2", "size", "20", "q", "")));
        assertEquals(all, withControl, "控制参数不得参与筛选");

        // 没有类型就没有可筛字段：全局列表路径下平铺参数一律忽略
        long global = total(listOf(null, null, null, Map.of("gender", "男")));
        assertEquals(all, global, "type 为空时平铺参数必须被忽略");
    }

    @Test
    void jsonFilterWinsWhenBothWritingsPresent() {
        long merged = total(listOf("student", null, "{\"gender\":\"女\"}", Map.of("gender", "男")));
        assertEquals(FEMALE_TOTAL, merged, "同名键以 filters=<JSON> 为准");
    }

    @Test
    void blankFlatValueIsNotApplied() {
        long all = total(listOf("student", null, null, Map.of()));
        long blank = total(listOf("student", null, null, Map.of("gender", "")));
        assertEquals(all, blank, "平铺参数为空字符串时等同于未筛选");
    }

    /** 走控制器真实入口（含平铺参数合并），只把查询服务替换成桩。 */
    private Map<String, Object> listOf(String type, String q, String filters,
                                       Map<String, String> params) {
        return controller.list(type, q, filters, 1, 20, null, params).data();
    }

    private static long total(Map<String, Object> data) {
        Object value = data.get("total");
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static Map<String, Object> listView(String type, Map<String, String> filters) {
        List<Map<String, String>> matched = new ArrayList<>();
        for (Map<String, String> student : students()) {
            if (matches(student, filters)) {
                matched.add(student);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("page", 1);
        data.put("size", 20);
        data.put("total", (long) matched.size());
        data.put("items", List.of());
        return data;
    }

    private static boolean matches(Map<String, String> student, Map<String, String> filters) {
        if (filters == null) {
            return true;
        }
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            // 与 OntologyRepository.appendFilterClause 同语义：空值跳过，其余按属性等值匹配
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (!entry.getValue().equals(student.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static List<Map<String, String>> students() {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int index = 1; index <= STUDENT_TOTAL; index++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", "学生" + index);
            row.put("gender", index <= MALE_TOTAL ? "男" : "女");
            row.put("className", "班级" + ((index % 4) + 1));
            rows.add(row);
        }
        return rows;
    }
}
