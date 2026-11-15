package com.school.ontology.core.projection;

import java.util.ArrayList;
import java.util.List;

/** 由 YAML 定义 + SPI 生成的一批投影数据（对象 / 关系 / 来源引用）。 */
public record SnapshotBatch(List<ProjectionRow> objects, List<com.school.ontology.core.spi.RelationInstance> relations,
                            List<SourceRefRow> sourceRefs) {

    public static SnapshotBatch empty() {
        return new SnapshotBatch(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public void merge(SnapshotBatch other) {
        objects.addAll(other.objects);
        relations.addAll(other.relations);
        sourceRefs.addAll(other.sourceRefs);
    }

    public boolean isEmpty() {
        return objects.isEmpty() && relations.isEmpty() && sourceRefs.isEmpty();
    }
}
