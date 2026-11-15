package com.school.ontology.support;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.projection.SourceRefRow;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.core.spi.RelationInstance;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试数据构造工具（全部虚拟数据，无真实业务数据）。 */
public final class TestFixtures {

    public static final String SOURCE_ID = "school-panorama";
    public static final String MAPPING_VERSION = "school-panorama:test00000001";

    private TestFixtures() {
    }

    public static String gid(String objectType, String pk) {
        return "gid:" + SOURCE_ID + ":" + objectType + ":" + pk;
    }

    public static ProjectionRow object(String objectType, String pk, long revision,
                                       Map<String, Object> attributes) {
        return new ProjectionRow(gid(objectType, pk), SOURCE_ID, objectType, pk,
                "虚拟" + objectType + pk, new LinkedHashMap<>(attributes),
                MAPPING_VERSION, revision, false);
    }

    public static RelationInstance relation(String type, String fromGid, String toGid,
                                            long revision) {
        return new RelationInstance(type + ":" + fromGid + ":" + toGid + ":NA",
                type, fromGid, toGid, "外键 " + type, "virtual_table", "1", "hash",
                MAPPING_VERSION, null, null, true, revision);
    }

    public static SourceRefRow sourceRef(String globalId, String pk) {
        return new SourceRefRow(globalId, SOURCE_ID, "virtual_table", pk,
                "V-" + pk, "hash", MAPPING_VERSION, 84L);
    }

    public static SnapshotBatch batch(ProjectionRow row, RelationInstance... relations) {
        List<ProjectionRow> objects = new ArrayList<>();
        objects.add(row);
        List<RelationInstance> rels = new ArrayList<>(List.of(relations));
        List<SourceRefRow> refs = new ArrayList<>();
        refs.add(sourceRef(row.globalId(), row.sourcePk()));
        return new SnapshotBatch(objects, rels, refs);
    }

    public static ModelRegistry minimalRegistry() {
        return new ModelRegistry(new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), SOURCE_ID);
    }

    public static OutboxEvent event(String eventId, long revision, String kind) {
        return new OutboxEvent(eventId, null, "LESSON_SESSION", "3", kind, revision,
                new LinkedHashMap<>(), LocalDateTime.of(2026, 11, 14, 10, 15),
                LocalDateTime.of(2026, 11, 14, 11, 0), "PENDING", 0);
    }
}
