package com.school.ontology.core.query;

import com.school.ontology.common.Resp;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.repository.OntologyRepository;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷回归（P3）：关系列表把中心对象自身的关系挤到第二页之后。
 *
 * <p>depth=2 会把中心边与邻居边一并返回（depth=1 已收敛为只看中心自己的边），
 * 旧实现只按 relationId 字典序排序，中心对象自己的关系可能排到第二页之后，
 * 于是「某对象的关系列表」第一页看不到它自己的关系。
 *
 * <p>修法：与中心 globalId 直接相连（from 或 to == 中心）的边优先，其余排后；
 * 同组内保持原有 relationId 排序。<b>分页协议、depth 语义、total 数值都不变。</b></p>
 */
class GraphServiceCenterEdgeFirstTest {

    private static final String CENTER = "school:1";

    private static RelationInstance relation(String relationId, String relationType,
                                            String from, String to) {
        return new RelationInstance(relationId, relationType, from, to,
                "测试关系", "test_table", "1", "hash-" + relationId, "mv-1",
                LocalDateTime.of(2026, 11, 1, 9, 0), null, true, 10);
    }

    /** 中心边 relationId 刻意排在邻居边之后（"r9" > "r1"），才能暴露旧排序的问题。 */
    private static GraphService graphService() {
        OntologyRepository repository = mock(OntologyRepository.class);
        when(repository.objectRow(anyString()))
                .thenReturn(Resp.map("object_type", "classroom", "title", "标题"));
        // 中心对象 -> 教室（这一条必须出现在第一页）
        when(repository.relationsFrom(CENTER, true)).thenReturn(List.of(
                relation("r9", "schoolHasClassroom", CENTER, "classroom:1")));
        // 邻居之间的关系（这一条应排到后面）
        when(repository.relationsFrom("classroom:1", true)).thenReturn(List.of(
                relation("r1", "classroomHasTeacher", "classroom:1", "teacher:5")));
        when(repository.relationsTo(anyString(), anyBoolean())).thenReturn(List.of());
        return new GraphService(repository, TestFixtures.minimalRegistry());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    @Test
    void 第一页优先返回中心自身的关系() {
        Map<String, Object> data = graphService().relations(CENTER, "both", 2, 1, 1);

        List<Map<String, Object>> page1 = items(data);
        assertEquals(1, page1.size(), "size=1 只返回 1 条");
        assertEquals("r9", page1.get(0).get("relationId"),
                "中心自身的关系必须排在第一页，不能被邻居之间的关系挤走");
        assertEquals(CENTER, ((Map<?, ?>) page1.get(0).get("from")).get("globalId"));
    }

    @Test
    void 邻居之间的关系排在中心关系之后且不丢失() {
        Map<String, Object> data = graphService().relations(CENTER, "both", 2, 2, 1);

        List<Map<String, Object>> page2 = items(data);
        assertEquals(1, page2.size());
        assertEquals("r1", page2.get(0).get("relationId"), "非中心边排后，仍能翻到");
    }

    @Test
    void 分页协议与总数深度语义保持不变() {
        Map<String, Object> data = graphService().relations(CENTER, "both", 2, 1, 1);

        assertEquals(CENTER, data.get("globalId"));
        assertEquals(2, data.get("depth"), "depth 回显不变");
        assertEquals(1, data.get("page"));
        assertEquals(1, data.get("size"));
        assertEquals(2, data.get("total"), "total 仍是全量条数，排序不改变总数");
        Map<String, Object> first = items(data).get(0);
        assertNotNull(first.get("relationType"));
        assertNotNull(first.get("displayName"));
        assertNotNull(first.get("from"));
        assertNotNull(first.get("to"));
        assertNotNull(first.get("active"), "关系视图字段集合不变");
    }
}
