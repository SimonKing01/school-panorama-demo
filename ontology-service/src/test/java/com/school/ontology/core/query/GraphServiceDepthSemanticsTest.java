package com.school.ontology.core.query;

import com.school.ontology.common.Resp;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.repository.OntologyRepository;
import com.school.ontology.support.InMemoryProjectionStore;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷回归（P4，阻断级）：关系探索接口在 depth=1 返回「邻居之间的边」且同一条关系重复，total 虚高。
 *
 * <p>根因：`expand(center, depth)` 的第 1 层本就返回「中心 + 一跳邻居」，
 * 旧实现直接遍历这些节点的出边/入边，于是 depth=1 也扫了邻居的边：
 * ① 混入邻居之间的关系；② 同一条边被中心正向 + 邻居反向各收一次，total 翻倍。</p>
 *
 * <p>正确语义：边集合 S = depth==1 ? {中心} : {中心 ∪ 一跳邻居}，
 * 保留 from ∈ S 或 to ∈ S 的边并按 relationId 去重，total 取去重后的条数。</p>
 *
 * <p>测试图：T 为中心，边 T→A、T→B、A→C、B→C（数据用 InMemoryProjectionStore 造）。</p>
 */
class GraphServiceDepthSemanticsTest {

    private static final String T = "gid:test:teacher:1";
    private static final String A = "gid:test:class:1";
    private static final String B = "gid:test:class:2";
    private static final String C = "gid:test:lessonSession:1";

    private static ProjectionRow object(String globalId, String title) {
        return new ProjectionRow(globalId, "test", "testType", globalId.substring(globalId.lastIndexOf(':') + 1),
                title, Map.of(), "mv-1", 10L, false);
    }

    private static RelationInstance relation(String relationId, String from, String to) {
        return new RelationInstance(relationId, "testRelation", from, to, "测试关系",
                "test_table", from + "-" + to, "hash-" + relationId, "mv-1",
                LocalDateTime.of(2026, 11, 1, 9, 0), null, true, 10L);
    }

    /** 图数据：T→A、T→B、A→C、B→C。 */
    private static InMemoryProjectionStore store() {
        InMemoryProjectionStore store = new InMemoryProjectionStore();
        store.upsertObject(object(T, "张老师"));
        store.upsertObject(object(A, "高一(1)班"));
        store.upsertObject(object(B, "高一(2)班"));
        store.upsertObject(object(C, "语文课 3"));
        store.upsertRelation(relation("r1", T, A));
        store.upsertRelation(relation("r2", T, B));
        store.upsertRelation(relation("r3", A, C));
        store.upsertRelation(relation("r4", B, C));
        return store;
    }

    /** 把内存投影适配成 GraphService 依赖的仓库读接口（只用到 objectRow / relationsFrom / relationsTo）。 */
    private static OntologyRepository repository(InMemoryProjectionStore store) {
        OntologyRepository repository = mock(OntologyRepository.class);
        when(repository.objectRow(anyString())).thenAnswer(invocation -> {
            ProjectionRow row = store.object(invocation.getArgument(0, String.class));
            return row == null ? Map.of()
                    : Resp.map("object_type", row.objectType(), "title", row.title());
        });
        when(repository.relationsFrom(anyString(), anyBoolean())).thenAnswer(invocation -> endpoint(
                store, invocation.getArgument(0, String.class), true));
        when(repository.relationsTo(anyString(), anyBoolean())).thenAnswer(invocation -> endpoint(
                store, invocation.getArgument(0, String.class), false));
        return repository;
    }

    /** 与 JDBC 实现语义一致：只取 active 且指定端匹配的关系。 */
    private static List<RelationInstance> endpoint(InMemoryProjectionStore store, String globalId,
                                                   boolean asFrom) {
        List<RelationInstance> result = new ArrayList<>();
        for (RelationInstance relation : store.relations()) {
            if (!relation.active()) {
                continue;
            }
            String endpoint = asFrom ? relation.fromGlobalId() : relation.toGlobalId();
            if (globalId != null && globalId.equals(endpoint)) {
                result.add(relation);
            }
        }
        return result;
    }

    private static GraphService service(InMemoryProjectionStore store) {
        return new GraphService(repository(store), TestFixtures.minimalRegistry());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    private static Set<String> relationIds(List<Map<String, Object>> items) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            assertTrue(ids.add(String.valueOf(item.get("relationId"))),
                    "同一条关系不得重复出现：" + item.get("relationId"));
        }
        return ids;
    }

    /** 该边是否触及中心 T（from 或 to）。 */
    private static boolean touchesCenter(Map<String, Object> item) {
        String from = String.valueOf(((Map<?, ?>) item.get("from")).get("globalId"));
        String to = String.valueOf(((Map<?, ?>) item.get("to")).get("globalId"));
        return T.equals(from) || T.equals(to);
    }

    @Test
    void depth1只返回中心自己的两条边且都触及中心() {
        Map<String, Object> data = service(store()).relations(T, "both", 1, 1, 300);

        List<Map<String, Object>> items = items(data);
        assertEquals(2, items.size(), "depth=1 只应返回 T 自己的 2 条边");
        assertEquals(2, data.get("total"), "total 必须等于真实条数");
        for (Map<String, Object> item : items) {
            assertTrue(touchesCenter(item), "depth=1 的每条边都必须触及中心对象：" + item.get("relationId"));
        }
        assertEquals(Set.of("r1", "r2"), relationIds(items));
    }

    @Test
    void depth1不得有重复relationId() {
        List<Map<String, Object>> items = items(service(store()).relations(T, "both", 1, 1, 300));

        long distinct = items.stream()
                .map(item -> String.valueOf(item.get("relationId")))
                .distinct()
                .count();
        assertEquals(items.size(), distinct, "total 虚高的回归点：重复边必须被 relationId 去重");
        assertEquals(2, distinct);
    }

    @Test
    void depth2返回中心边加邻居边且无重复() {
        Map<String, Object> data = service(store()).relations(T, "both", 2, 1, 300);

        List<Map<String, Object>> items = items(data);
        // T→A、T→B（中心边）+ A→C、B→C（邻居边），每条只算一次。
        assertEquals(4, items.size(), "depth=2 应返回 4 条去重后的边");
        assertEquals(4, data.get("total"));
        assertEquals(Set.of("r1", "r2", "r3", "r4"), relationIds(items));
    }

    @Test
    void depth1加forward只返回中心出边() {
        Map<String, Object> data = service(store()).relations(T, "forward", 1, 1, 300);

        List<Map<String, Object>> items = items(data);
        assertEquals(2, items.size());
        for (Map<String, Object> item : items) {
            assertEquals(T, ((Map<?, ?>) item.get("from")).get("globalId"),
                    "forward 只取 from == 中心 的边");
        }
        assertEquals(Set.of("r1", "r2"), relationIds(items));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 关系图depth1是干净星形且depth2不产生孤点() {
        GraphService service = service(store());

        Map<String, Object> star = service.graph(T, 1, 50, 1);
        List<Map<String, Object>> starNodes = (List<Map<String, Object>>) star.get("nodes");
        List<Map<String, Object>> starEdges = (List<Map<String, Object>>) star.get("edges");
        assertEquals(3, starNodes.size(), "depth=1 的节点是 T + 两个一跳邻居");
        assertEquals(2, starEdges.size(), "depth=1 的图只画中心自己的边（不能出现邻居之间的边）");
        for (Map<String, Object> edge : starEdges) {
            assertTrue(T.equals(edge.get("from")) || T.equals(edge.get("to")),
                    "depth=1 的边必须触及中心：" + edge.get("relationId"));
        }

        Map<String, Object> wide = service.graph(T, 2, 50, 1);
        List<Map<String, Object>> wideNodes = (List<Map<String, Object>>) wide.get("nodes");
        List<Map<String, Object>> wideEdges = (List<Map<String, Object>>) wide.get("edges");
        assertEquals(4, wideNodes.size(), "depth=2 的节点含两跳对象 C");
        assertEquals(4, wideEdges.size(), "depth=2 的边是 4 条去重后的边");
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> edge : wideEdges) {
            assertTrue(ids.add(String.valueOf(edge.get("relationId"))), "关系图的边也不得重复");
        }
    }
}
