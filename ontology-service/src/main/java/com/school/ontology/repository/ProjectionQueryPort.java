package com.school.ontology.repository;

import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.spi.QueryPort;
import com.school.ontology.core.spi.RelationInstance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** QueryPort 的本体库只读实现：Resolver / Provider 通过它读投影，绝不写库。 */
@Component
public class ProjectionQueryPort implements QueryPort {

    private final OntologyRepository repository;

    public ProjectionQueryPort(OntologyRepository repository) {
        this.repository = repository;
    }

    @Override
    public ProjectionRow object(String globalId) {
        return repository.findObject(globalId);
    }

    @Override
    public Map<String, Object> attributes(String globalId) {
        ProjectionRow row = repository.findObject(globalId);
        return row == null ? Map.of() : row.attributes();
    }

    @Override
    public String title(String globalId) {
        ProjectionRow row = repository.findObject(globalId);
        return row == null ? globalId : row.title();
    }

    @Override
    public List<RelationInstance> relationsFrom(String globalId, boolean includeInactive) {
        return repository.relationsFrom(globalId, includeInactive);
    }

    @Override
    public List<RelationInstance> relationsTo(String globalId, boolean includeInactive) {
        return repository.relationsTo(globalId, includeInactive);
    }

    @Override
    public List<ProjectionRow> objectsOfType(String objectType) {
        List<ProjectionRow> result = new ArrayList<>();
        for (ProjectionRow row : repository.allObjects()) {
            if (objectType.equals(row.objectType()) && !row.deleted()) {
                result.add(row);
            }
        }
        return result;
    }

    @Override
    public long countRelationsTo(String globalId, String relationType) {
        long count = 0L;
        for (RelationInstance relation : repository.relationsTo(globalId, false)) {
            if (relationType.equals(relation.relationType())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public List<String> distinctFromGlobalIds(String globalId, String relationType) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (RelationInstance relation : repository.relationsTo(globalId, true)) {
            if (relationType.equals(relation.relationType())) {
                result.add(relation.fromGlobalId());
            }
        }
        return new ArrayList<>(result);
    }
}
