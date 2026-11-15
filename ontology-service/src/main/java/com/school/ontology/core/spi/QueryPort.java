package com.school.ontology.core.spi;

import com.school.ontology.core.projection.ProjectionRow;

import java.util.List;
import java.util.Map;

/** 只读投影查询端口：DynamicResolver/Provider 通过它读对象与关系，绝不写库。 */
public interface QueryPort {

    ProjectionRow object(String globalId);

    Map<String, Object> attributes(String globalId);

    String title(String globalId);

    List<RelationInstance> relationsFrom(String globalId, boolean includeInactive);

    List<RelationInstance> relationsTo(String globalId, boolean includeInactive);

    List<ProjectionRow> objectsOfType(String objectType);

    long countRelationsTo(String globalId, String relationType);

    List<String> distinctFromGlobalIds(String globalId, String relationType);
}
