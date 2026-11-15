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
        Set<String> nodeIds = expand(globalId, depth, 200);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String node : nodeIds) {
            boolean forward = direction == null || !"reverse".equals(direction);
            boolean reverse = direction == null || !"forward".equals(direction);
            if (forward) {
                for (RelationInstance relation : repository.relationsFrom(node, true)) {
                    items.add(relationView(relation));
                }
            }
            if (reverse) {
                for (RelationInstance relation : repository.relationsTo(node, true)) {
                    items.add(relationView(relation));
                }
            }
        }
        items.sort((a, b) -> String.valueOf(a.get("relationId"))
                .compareTo(String.valueOf(b.get("relationId"))));
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
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seenEdges = new LinkedHashSet<>();
        for (String gid : ordered) {
            for (RelationInstance relation : repository.relationsFrom(gid, true)) {
                if (!ordered.contains(relation.toGlobalId()) || !seenEdges.add(relation.relationId())) {
                    continue;
                }
                Map<String, Object> edge = Resp.map(
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
                edges.add(edge);
            }
            for (RelationInstance relation : repository.relationsTo(gid, true)) {
                if (!ordered.contains(relation.fromGlobalId()) || !seenEdges.add(relation.relationId())) {
                    continue;
                }
                edges.add(Resp.map(
                        "relationId", relation.relationId(),
                        "relationType", relation.relationType(),
                        "from", relation.fromGlobalId(),
                        "to", relation.toGlobalId(),
                        "active", relation.active(),
                        "label", labelOf(relation.relationType(), relation.active()),
                        "reason", relation.reason(),
                        "basis", relation.active()
                                ? "关系有效（revision " + relation.appliedRevision() + " 建立或确认）"
                                : "旧关系已结束（validTo " + Resp.ts(relation.validTo()) + "）"));
            }
        }
        return Resp.map("center", globalId, "depth", depth, "limit", limit, "page", page,
                "truncated", truncated, "nodes", nodes, "edges", edges);
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
