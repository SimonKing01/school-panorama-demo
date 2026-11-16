package com.school.ontology.core.query;

import com.school.ontology.common.ApiException;
import com.school.ontology.common.Resp;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.RelationTypeDef;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.repository.OntologyRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 通用关系探索：列表 + 关系图，1/2 层展开、节点上限与分页，点边可看 reason/basis。 */
@Component
public class GraphService {

    private final OntologyRepository repository;
    private final ModelRegistry registry;

    public GraphService(OntologyRepository repository, ModelRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    public Map<String, Object> relations(String globalId, String direction, int depth,
                                         int page, int size) {
        validateDepth(depth);
        ensureObject(globalId);
        boolean forward = direction == null || !"reverse".equals(direction);
        boolean reverse = direction == null || !"forward".equals(direction);
        // 边集合规则：depth=1 只看中心自己（用户期望「这个对象有哪些关系」），
        // depth=2 才扩到「中心 ∪ 一跳邻居」。旧实现直接遍历 expand(center, depth) 的节点，
        // 而 expand 的第 1 层本就含中心+一跳邻居，于是 depth=1 也扫了邻居的边：
        // 既混入「邻居之间的关系」，又让同一条边被中心正向 + 邻居反向各收一次（total 虚高）。
        Map<String, RelationInstance> collected = new LinkedHashMap<>();
        for (String node : edgeScope(globalId, depth)) {
            if (forward) {
                for (RelationInstance relation : repository.relationsFrom(node, true)) {
                    collected.putIfAbsent(relation.relationId(), relation);
                }
            }
            if (reverse) {
                for (RelationInstance relation : repository.relationsTo(node, true)) {
                    collected.putIfAbsent(relation.relationId(), relation);
                }
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (RelationInstance relation : collected.values()) {
            items.add(relationView(relation));
        }
        // 与中心对象直接相连的边排在最前，其余（depth=2 时邻居之间的关系）排后：
        // 否则按 relationId 自然序时，中心自身的关系可能被挤到第二页之后。
        items.sort(Comparator
                .comparingInt((Map<String, Object> view) -> centerAdjacent(view, globalId) ? 0 : 1)
                .thenComparing(view -> String.valueOf(view.get("relationId"))));
        // total = 去重后的真实条数，分页协议不变。
        int total = items.size();
        int from = Math.min(Math.max(page - 1, 0) * size, total);
        int to = Math.min(from + size, total);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("globalId", globalId);
        data.put("depth", depth);
        data.put("page", page);
        data.put("size", size);
        data.put("total", total);
        data.put("items", items.subList(from, to));
        return data;
    }

    public Map<String, Object> graph(String globalId, int depth, int limit, int page) {
        validateDepth(depth);
        if (limit <= 0 || limit > 200) {
            throw ApiException.badRequest("limit 必须在 1..200 之间");
        }
        ensureObject(globalId);
        Set<String> nodeIds = expand(globalId, depth, limit + 1);
        boolean truncated = nodeIds.size() > limit;
        List<String> ordered = new ArrayList<>(nodeIds).subList(0, Math.min(nodeIds.size(), limit));

        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Integer> layers = layersOf(globalId, depth, limit);
        for (String gid : ordered) {
            Map<String, Object> row = repository.objectRow(gid);
            nodes.add(Resp.map("globalId", gid,
                    "type", OntologyRepository.str(row.get("object_type")),
                    "title", OntologyRepository.str(row.get("title")),
                    "layer", layers.getOrDefault(gid, depth)));
        }
        // 边与 relations() 共用同一套作用域语义：depth=1 只画中心自己的星形，
        // depth=2 才画「中心 ∪ 一跳邻居」的边；同一条边会被中心正向与邻居反向各收一次，
        // 必须按 relationId 去重，否则既重复又把 total 抬高。
        Map<String, RelationInstance> collected = new LinkedHashMap<>();
        for (String gid : edgeScope(globalId, depth)) {
            for (RelationInstance relation : repository.relationsFrom(gid, true)) {
                if (ordered.contains(relation.fromGlobalId()) && ordered.contains(relation.toGlobalId())) {
                    collected.putIfAbsent(relation.relationId(), relation);
                }
            }
            for (RelationInstance relation : repository.relationsTo(gid, true)) {
                if (ordered.contains(relation.fromGlobalId()) && ordered.contains(relation.toGlobalId())) {
                    collected.putIfAbsent(relation.relationId(), relation);
                }
            }
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (RelationInstance relation : collected.values()) {
            edges.add(edgeView(relation));
        }
        return Resp.map("center", globalId, "depth", depth, "limit", limit, "page", page,
                "truncated", truncated, "nodes", nodes, "edges", edges);
    }

    /** 关系图里的一条边（与关系列表的 relationView 分离：图只需要两端的 globalId 字符串）。 */
    private Map<String, Object> edgeView(RelationInstance relation) {
        return Resp.map(
                "relationId", relation.relationId(),
                "relationType", relation.relationType(),
                "from", relation.fromGlobalId(),
                "to", relation.toGlobalId(),
                "active", relation.active(),
                "label", labelOf(relation.relationType(), relation.active()),
                "reason", relation.reason(),
                "basis", relation.active()
                        ? "关系有效（revision " + relation.appliedRevision() + " 建立或确认）"
                        : "旧关系已结束（validTo " + Resp.ts(relation.validTo()) + "）");
    }

    public Map<String, Object> relationView(RelationInstance relation) {
        RelationTypeDef def = registry.hasRelation(relation.relationType())
                ? registry.relationOf(relation.relationType()) : null;
        Map<String, Object> from = Resp.map("globalId", relation.fromGlobalId(),
                "title", objectTitle(relation.fromGlobalId()));
        Map<String, Object> to = Resp.map("globalId", relation.toGlobalId(),
                "title", objectTitle(relation.toGlobalId()));
        return Resp.map(
                "relationId", relation.relationId(),
                "relationType", relation.relationType(),
                "displayName", def == null ? relation.relationType() : def.forwardName,
                "from", from, "to", to,
                "reason", relation.reason(),
                "generationKind", def == null || def.generation == null ? null : def.generation.kind,
                "source", Resp.map("sourceTable", relation.sourceTable(),
                        "sourcePk", relation.sourcePk(), "sourceHash", relation.sourceHash()),
                "mappingVersion", relation.mappingVersion(),
                "validFrom", Resp.ts(relation.validFrom()),
                "validTo", Resp.ts(relation.validTo()),
                "active", relation.active(),
                "appliedRevision", relation.appliedRevision());
    }

    /** 该边是否与中心对象直接相连（from 或 to 等于中心 globalId）；用于排序分组，不影响返回字段。 */
    private static boolean centerAdjacent(Map<String, Object> view, String centerGlobalId) {
        if (view == null || centerGlobalId == null) {
            return false;
        }
        return centerGlobalId.equals(endGlobalId(view.get("from")))
                || centerGlobalId.equals(endGlobalId(view.get("to")));
    }

    /** 从关系端点的嵌套结构里取 globalId；结构缺失时返回 null（不抛异常，排序容错）。 */
    private static String endGlobalId(Object endpoint) {
        if (endpoint instanceof Map<?, ?> map) {
            Object globalId = map.get("globalId");
            return globalId == null ? null : String.valueOf(globalId);
        }
        return null;
    }

    /**
     * 边集合作用域 S：depth=1 只含中心对象本身，depth=2 才含「中心 ∪ 一跳邻居」。
     *
     * <p>只遍历 S 内节点的出边/入边，就等价于「保留 from ∈ S 或 to ∈ S 的边」，
     * 再用 relationId 去重即可消除「中心正向 + 邻居反向」造成的重复。</p>
     */
    private Set<String> edgeScope(String center, int depth) {
        if (depth <= 1) {
            return Set.of(center);
        }
        return expand(center, 1, 200);
    }

    private void ensureObject(String globalId) {
        if (repository.objectRow(globalId).isEmpty()) {
            throw ApiException.notFound("对象不存在：" + globalId);
        }
    }

    private void validateDepth(int depth) {
        if (depth < 1 || depth > 2) {
            throw ApiException.badRequest("depth 只允许 1 或 2");
        }
    }

    private String labelOf(String relationType, boolean active) {
        RelationTypeDef def = registry.hasRelation(relationType) ? registry.relationOf(relationType) : null;
        String name = def == null ? relationType : def.forwardName;
        return active ? name : name + "(已结束)";
    }

    private String objectTitle(String globalId) {
        Map<String, Object> row = repository.objectRow(globalId);
        return row.isEmpty() ? globalId : OntologyRepository.str(row.get("title"));
    }

    private Set<String> expand(String center, int depth, int limit) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(center);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(center);
        for (int level = 0; level < depth && !queue.isEmpty() && visited.size() < limit; level++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                if (current == null) {
                    continue;
                }
                for (RelationInstance relation : repository.relationsFrom(current, true)) {
                    if (visited.size() < limit && visited.add(relation.toGlobalId())) {
                        queue.add(relation.toGlobalId());
                    }
                }
                for (RelationInstance relation : repository.relationsTo(current, true)) {
                    if (visited.size() < limit && visited.add(relation.fromGlobalId())) {
                        queue.add(relation.fromGlobalId());
                    }
                }
            }
        }
        return visited;
    }

    private Map<String, Integer> layersOf(String center, int depth, int limit) {
        Map<String, Integer> layers = new LinkedHashMap<>();
        layers.put(center, 0);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(center);
        for (int level = 0; level < depth && !queue.isEmpty() && layers.size() < limit; level++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                if (current == null) {
                    continue;
                }
                for (RelationInstance relation : repository.relationsFrom(current, true)) {
                    if (layers.size() < limit && layers.putIfAbsent(relation.toGlobalId(), level + 1) == null) {
                        queue.add(relation.toGlobalId());
                    }
                }
                for (RelationInstance relation : repository.relationsTo(current, true)) {
                    if (layers.size() < limit && layers.putIfAbsent(relation.fromGlobalId(), level + 1) == null) {
                        queue.add(relation.fromGlobalId());
                    }
                }
            }
        }
        return layers;
    }
}
