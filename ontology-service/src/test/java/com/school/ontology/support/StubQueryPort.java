package com.school.ontology.support;

import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.spi.QueryPort;
import com.school.ontology.core.spi.RelationInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读投影端口的内存替身，用于验证动态 Resolver 不写库、只依赖 queryTime。 */
public class StubQueryPort implements QueryPort {

    private final Map<String, ProjectionRow> objects = new LinkedHashMap<>();
    private final Map<String, List<RelationInstance>> from = new LinkedHashMap<>();
    private final Map<String, List<RelationInstance>> to = new LinkedHashMap<>();

    public StubQueryPort addObject(ProjectionRow row) {
        objects.put(row.globalId(), row);
        return this;
    }

    public StubQueryPort addRelationFrom(String gid, RelationInstance relation) {
        from.computeIfAbsent(gid, ignored -> new ArrayList<>()).add(relation);
        return this;
    }

    public StubQueryPort addRelationTo(String gid, RelationInstance relation) {
        to.computeIfAbsent(gid, ignored -> new ArrayList<>()).add(relation);
        return this;
    }

    @Override
    public ProjectionRow object(String globalId) {
        return objects.get(globalId);
    }

    @Override
    public Map<String, Object> attributes(String globalId) {
        ProjectionRow row = objects.get(globalId);
        return row == null ? Map.of() : row.attributes();
    }

    @Override
    public String title(String globalId) {
        ProjectionRow row = objects.get(globalId);
        return row == null ? globalId : row.title();
    }

    @Override
    public List<RelationInstance> relationsFrom(String globalId, boolean includeInactive) {
        return from.getOrDefault(globalId, List.of());
    }

    @Override
    public List<RelationInstance> relationsTo(String globalId, boolean includeInactive) {
        return to.getOrDefault(globalId, List.of());
    }

    @Override
    public List<ProjectionRow> objectsOfType(String objectType) {
        List<ProjectionRow> result = new ArrayList<>();
        for (ProjectionRow row : objects.values()) {
            if (objectType.equals(row.objectType())) {
                result.add(row);
            }
        }
        return result;
    }

    @Override
    public long countRelationsTo(String globalId, String relationType) {
        return to.getOrDefault(globalId, List.of()).stream()
                .filter(r -> relationType.equals(r.relationType())).count();
    }

    @Override
    public List<String> distinctFromGlobalIds(String globalId, String relationType) {
        List<String> result = new ArrayList<>();
        for (RelationInstance relation : to.getOrDefault(globalId, List.of())) {
            if (relationType.equals(relation.relationType())) {
                result.add(relation.fromGlobalId());
            }
        }
        return result;
    }
}
