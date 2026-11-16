package com.school.ontology.core.sync;

import com.school.ontology.core.model.GlobalIds;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectMappingDef;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.projection.SnapshotBuilder;
import com.school.ontology.core.projection.SourceRefRow;
import com.school.ontology.core.spi.BusinessTime;
import com.school.ontology.core.spi.LookupResolver;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.core.spi.SourceAdapter;
import com.school.ontology.core.spi.SourceQuery;
import com.school.ontology.core.spi.SourceRecord;
import com.school.ontology.support.InMemoryProjectionStore;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷回归：关系换边后「相关对象」的派生属性会滞后（旧教室仍显示导入时的排课数）。
 *
 * <p>三类验收：
 * 一、事件计划必须为相关对象补出刷新批次（放在 refreshBatches，只带对象与来源引用，
 * 不带关系，且不进 SELF_CHANGED）；
 * 二、邻居刷新**只写对象属性，绝不能停掉邻居已有的关系**（批次④ 阻断级回归）；
 * 三、刷新批次与重复事件不得刷出「属性其实没变」的对象版本行，变更历史只记真实关系增删。</p>
 */
class RelatedObjectRefreshTest {

    private static final String SOURCE_ID = TestFixtures.SOURCE_ID;
    private static final String LESSON_TYPE = "lessonSession";
    private static final String CLASSROOM_TYPE = "classroom";
    private static final String TEACHER_TYPE = "teacher";
    private static final String SCHOOL_TYPE = "school";
    private static final String LESSON_TABLE = "lesson_sessions";
    private static final String CLASSROOM_TABLE = "classrooms";
    private static final String TEACHER_TABLE = "teachers";
    private static final String SCHOOL_TABLE = "schools";

    /** 类型 → 来源表（测试替身用，避免在测试里写类型分支）。 */
    private static final Map<String, String> TABLE_OF_TYPE = Map.of(
            LESSON_TYPE, LESSON_TABLE,
            CLASSROOM_TYPE, CLASSROOM_TABLE,
            TEACHER_TYPE, TEACHER_TABLE,
            SCHOOL_TYPE, SCHOOL_TABLE);

    private static final String TAUGHT_BY = "session_taught_by";
    private static final String IN_CLASSROOM = "session_in_classroom";

    private final InMemoryProjectionStore store = new InMemoryProjectionStore();
    private final ProjectionWriter writer = new ProjectionWriter(store, TestFixtures.minimalRegistry());

    private final String lessonGid = TestFixtures.gid(LESSON_TYPE, "3");
    private final String classroom1Gid = TestFixtures.gid(CLASSROOM_TYPE, "1");
    private final String classroom6Gid = TestFixtures.gid(CLASSROOM_TYPE, "6");
    private final String classroom999Gid = TestFixtures.gid(CLASSROOM_TYPE, "999");
    private final String teacher5Gid = TestFixtures.gid(TEACHER_TYPE, "5");
    private final String teacher8Gid = TestFixtures.gid(TEACHER_TYPE, "8");
    private final String school1Gid = TestFixtures.gid(SCHOOL_TYPE, "1");

    // ───────── 一、事件计划：相关对象放进 refreshBatches ─────────

    @Test
    void 事件计划为相关对象补出刷新批次且不进自身变更集() {
        FakeAdapter adapter = new FakeAdapter();
        EventPlan plan = new SourceDbEventSnapshotResolver(adapter, registry(),
                new ModelDefinitionLoader(), new FakeBuilder(), snapshotSource()).plan(event());

        assertEquals(1, plan.batches().size(), "自身批次只有一个（授课安排 3）");
        assertEquals(3, plan.refreshBatches().size(),
                "三个可刷新的邻居（教室 1、教室 6、教师 5）；教室 999 来源缺失应跳过");

        List<String> refreshGids = new ArrayList<>();
        for (SnapshotBatch batch : plan.refreshBatches()) {
            for (ProjectionRow row : batch.objects()) {
                refreshGids.add(row.globalId());
            }
        }
        assertTrue(refreshGids.contains(classroom1Gid));
        assertTrue(refreshGids.contains(classroom6Gid));
        assertTrue(refreshGids.contains(teacher5Gid));
        assertFalse(refreshGids.contains(classroom999Gid), "来源行不存在的邻居不得生成刷新批次");

        assertEquals(List.of(lessonGid), plan.selfGlobalIds(), "邻居绝不能被标成 SELF_CHANGED");
        assertTrue(plan.relatedGlobalIds().contains(classroom1Gid));
        assertTrue(plan.relatedGlobalIds().contains(teacher5Gid));
        assertFalse(plan.relatedGlobalIds().contains(lessonGid), "自身对象不应同时出现在相关对象里");
        assertTrue(plan.tombstoneGlobalIds().isEmpty(), "邻居缺席绝不能被当成删除（不标墓碑）");
        assertEquals(List.of(LESSON_TYPE + ":3", CLASSROOM_TYPE + ":1", CLASSROOM_TYPE + ":6",
                        CLASSROOM_TYPE + ":999", TEACHER_TYPE + ":5"), adapter.scans(),
                "自身与每个相关对象各自只重扫一次（按事件内去重，顺序稳定）");

        SnapshotBatch selfBatch = batchOf(plan, lessonGid);
        assertEquals(1, selfBatch.relations().size(), "自身批次仍需重算关系（换边落在这里）");
        for (String neighborGid : List.of(classroom1Gid, classroom6Gid, teacher5Gid)) {
            SnapshotBatch neighborBatch = batchOf(plan, neighborGid);
            assertTrue(neighborBatch.relations().isEmpty(),
                    "邻居批次不得携带关系：" + neighborGid + "（邻居关系不由本次事件重算）");
            assertEquals(1, neighborBatch.sourceRefs().size(), "邻居批次仍要刷新来源引用");
            assertEquals(85L, neighborBatch.objects().get(0).attributes().get("refreshedAt"),
                    "邻居属性必须按本次事件水位重算");
        }
    }

    // ───────── 二、邻居刷新不得动邻居的关系（阻断级回归） ─────────

    @Test
    void 邻居刷新不改变邻居已有的有效关系() {
        store.upsertObject(row(CLASSROOM_TYPE, "1", 84, Map.of("name", "明理楼101", "sessionCount", 4)));
        store.upsertObject(row(LESSON_TYPE, "3", 84, Map.of("teacherName", "张明远")));
        // 教室 1 业务上本来就有两条有效出边：所属学校、与本次事件无关的另一条。
        RelationInstance inSchool = TestFixtures.relation("classroom_in_school", classroom1Gid, school1Gid, 84);
        RelationInstance toSchool = TestFixtures.relation("classroom_supports_lab", classroom1Gid, school1Gid, 84);
        store.upsertRelation(inSchool);
        store.upsertRelation(toSchool);

        EventPlan plan = new EventPlan(
                List.of(batch(row(LESSON_TYPE, "3", 85, Map.of("teacherName", "张明远")))),
                List.of(), Map.of(), List.of(lessonGid), List.of(classroom1Gid),
                Map.of("changeKind", "ADJUST"),
                List.of(batch(row(CLASSROOM_TYPE, "1", 85,
                        Map.of("name", "明理楼101", "sessionCount", 3)))),
                List.of());
        writer.applyEvent(eventOf("evt-1", 85), plan);

        assertEquals(3, intAttribute(store.object(classroom1Gid), "sessionCount"),
                "邻居属性必须被刷新");
        assertEquals(2, store.activeRelationsFrom(classroom1Gid).size(),
                "邻居原有的两条出边必须仍然有效（不得被刷新批次误停）");
        ChangeRecordRow change = store.lastChange();
        assertTrue(change.relationsRemoved().isEmpty(),
                "没有任何关系真正结束，变更历史不得出现假解除：" + change.relationsRemoved());
        assertTrue(change.relationsAdded().isEmpty());
    }

    // ───────── 三、落库：只记真实变化 ─────────

    @Test
    void 变更历史只记录真实关系增删() {
        store.upsertObject(row(LESSON_TYPE, "3", 84, Map.of("teacherName", "张明远")));
        store.upsertObject(row(TEACHER_TYPE, "5", 84, Map.of("name", "张明远")));
        store.upsertObject(row(TEACHER_TYPE, "8", 84, Map.of("name", "陈思琪")));
        store.upsertObject(row(CLASSROOM_TYPE, "1", 84, Map.of("name", "明理楼101", "sessionCount", 4)));
        store.upsertRelation(TestFixtures.relation(TAUGHT_BY, lessonGid, teacher5Gid, 84));
        store.upsertRelation(TestFixtures.relation(IN_CLASSROOM, lessonGid, classroom1Gid, 84));
        store.upsertRelation(TestFixtures.relation("classroom_in_school", classroom1Gid, school1Gid, 84));

        // 事件：授课安排 3 的教师换成 8（课堂仍在教室 1），并顺带刷新教室 1 的派生属性。
        RelationInstance newTeacher = TestFixtures.relation(TAUGHT_BY, lessonGid, teacher8Gid, 85);
        RelationInstance sameRoom = TestFixtures.relation(IN_CLASSROOM, lessonGid, classroom1Gid, 85);
        EventPlan plan = new EventPlan(
                List.of(new SnapshotBatch(
                        List.of(row(LESSON_TYPE, "3", 85, Map.of("teacherName", "陈思琪"))),
                        List.of(newTeacher, sameRoom), List.of())),
                List.of(), Map.of(), List.of(lessonGid),
                List.of(classroom1Gid), Map.of("changeKind", "ADJUST"),
                List.of(batch(row(CLASSROOM_TYPE, "1", 85,
                        Map.of("name", "明理楼101", "sessionCount", 4)))),
                List.of());
        writer.applyEvent(eventOf("evt-1", 85), plan);

        ChangeRecordRow change = store.lastChange();
        assertEquals(1, change.relationsRemoved().size(),
                "只有旧教师边真正结束：" + change.relationsRemoved());
        assertEquals(teacher5Gid, change.relationsRemoved().get(0).get("toGlobalId"));
        assertEquals(1, change.relationsAdded().size(),
                "只有新教师边真正建立（同一教室关系未被重算）：" + change.relationsAdded());
        assertEquals(teacher8Gid, change.relationsAdded().get(0).get("toGlobalId"));

        assertEquals(1, store.activeRelationsFrom(classroom1Gid).size(),
                "教室 1 的所属学校关系必须仍然有效");
        assertEquals(2, store.activeRelationsFrom(lessonGid).size(),
                "授课安排当前有效出边 = 新教师边 + 教室边");
    }

    @Test
    void 邻居批次刷新派生属性而版本只记真实变化() {
        store.upsertObject(row(LESSON_TYPE, "3", 84, Map.of("classroomName", "明理楼101")));
        store.upsertObject(row(CLASSROOM_TYPE, "1", 84, Map.of("name", "明理楼101", "sessionCount", 4)));
        store.upsertObject(row(CLASSROOM_TYPE, "6", 84, Map.of("name", "多功能报告厅", "sessionCount", 1)));
        store.upsertObject(row(TEACHER_TYPE, "5", 84, Map.of("name", "张明远")));

        EventPlan plan = new EventPlan(
                List.of(batch(row(LESSON_TYPE, "3", 85, Map.of("classroomName", "多功能报告厅")))),
                List.of(), Map.of(), List.of(lessonGid),
                List.of(classroom1Gid, classroom6Gid, teacher5Gid),
                Map.of("changeKind", "ADJUST"),
                // 教室 1 少一节课（4→3）、教室 6 多一节课（1→2）；教师 5 无变化。
                List.of(batch(row(CLASSROOM_TYPE, "1", 85, Map.of("name", "明理楼101", "sessionCount", 3))),
                        batch(row(CLASSROOM_TYPE, "6", 85, Map.of("name", "多功能报告厅", "sessionCount", 2))),
                        batch(row(TEACHER_TYPE, "5", 85, Map.of("name", "张明远")))),
                List.of());
        writer.applyEvent(eventOf("evt-1", 85), plan);

        assertEquals(3, intAttribute(store.object(classroom1Gid), "sessionCount"),
                "旧教室的派生属性必须被刷新为最新值");
        assertEquals(2, intAttribute(store.object(classroom6Gid), "sessionCount"));
        assertEquals("多功能报告厅", store.object(lessonGid).attributes().get("classroomName"));

        assertEquals(1, versionCount(classroom1Gid), "确实变化的邻居要留一条版本");
        assertEquals(1, versionCount(classroom6Gid));
        assertEquals(1, versionCount(lessonGid));
        assertEquals(0, versionCount(teacher5Gid), "属性没变的邻居不得刷出无变化版本行");
        assertEquals(1, store.changeHistoryCount(), "一次事件只产生一条变更历史");
    }

    @Test
    void 重复事件属性未变化时不追加对象版本() {
        store.upsertObject(row(LESSON_TYPE, "3", 84, Map.of("teacherName", "张明远")));
        writer.applyEvent(eventOf("evt-1", 85), planOf(85, "陈思琪"));
        assertEquals(1, versionCount(lessonGid));

        writer.applyEvent(eventOf("evt-2", 86), planOf(86, "陈思琪"));
        assertEquals(1, versionCount(lessonGid), "属性未变化不得追加版本行");
        assertEquals(86L, store.object(lessonGid).appliedRevision(), "版本水位仍必须推进");
        assertEquals(2, store.changeHistoryCount(), "变更历史仍按事件条数记录");
    }

    // ───────── 辅助 ─────────

    private EventPlan planOf(long revision, String teacherName) {
        return new EventPlan(
                List.of(batch(row(LESSON_TYPE, "3", revision, Map.of("teacherName", teacherName)))),
                List.of(), Map.of(), List.of(lessonGid), List.of(),
                Map.of("changeKind", "ADJUST"), List.of(), List.of());
    }

    private static SnapshotBatch batch(ProjectionRow row) {
        return new SnapshotBatch(List.of(row), new ArrayList<>(), List.of());
    }

    private static ProjectionRow row(String objectType, String pk, long revision,
                                     Map<String, Object> attributes) {
        return new ProjectionRow(TestFixtures.gid(objectType, pk), SOURCE_ID, objectType, pk,
                "标题-" + objectType + pk, new LinkedHashMap<>(attributes),
                TestFixtures.MAPPING_VERSION, revision, false);
    }

    private static int intAttribute(ProjectionRow row, String name) {
        Object value = row.attributes().get(name);
        return value instanceof Number number ? number.intValue() : -1;
    }

    private long versionCount(String globalId) {
        return store.objectVersions().stream()
                .filter(version -> globalId.equals(version.get("globalId")))
                .count();
    }

    /** 自身批次与刷新批次都可能在计划里，按 globalId 查所在批次。 */
    private static SnapshotBatch batchOf(EventPlan plan, String globalId) {
        List<SnapshotBatch> all = new ArrayList<>(plan.batches());
        all.addAll(plan.refreshBatches());
        for (SnapshotBatch batch : all) {
            for (ProjectionRow row : batch.objects()) {
                if (globalId.equals(row.globalId())) {
                    return batch;
                }
            }
        }
        throw new AssertionError("事件计划里没有该对象的批次：" + globalId);
    }

    private static OutboxEvent eventOf(String eventId, long revision) {
        return new OutboxEvent(eventId, null, "LESSON_SESSION", "3", "ADJUST", revision,
                Map.of(), LocalDateTime.of(2026, 11, 14, 10, 15),
                LocalDateTime.of(2026, 11, 14, 11, 0), "PENDING", 0);
    }

    /**
     * 复现业务侧 payload：items 只给自身对象（授课安排 3），impact 给出受影响的
     * 新旧教室（1、6、999）与教师（5）。999 用来验证「邻居来源缺失就跳过」。
     */
    private static OutboxEvent event() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceTable", LESSON_TABLE);
        item.put("sourcePk", "3");
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("classroomIds", List.of(1, 6, 999));
        impact.put("teacherIds", List.of(5));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", List.of(item));
        payload.put("impact", impact);
        return new OutboxEvent("evt-related", null, "LESSON_SESSION", "3", "ADJUST", 85,
                payload, LocalDateTime.of(2026, 11, 14, 10, 15),
                LocalDateTime.of(2026, 11, 14, 11, 0), "PENDING", 0);
    }

    /** 最小模型注册表：只声明类型与来源表，够事件解析判定「类型是否存在 / 表归属哪个类型」。 */
    private static ModelRegistry registry() {
        Map<String, ObjectTypeDef> types = new LinkedHashMap<>();
        Map<String, ObjectMappingDef> mappings = new LinkedHashMap<>();
        register(types, mappings, LESSON_TYPE, LESSON_TABLE);
        register(types, mappings, CLASSROOM_TYPE, CLASSROOM_TABLE);
        register(types, mappings, TEACHER_TYPE, TEACHER_TABLE);
        register(types, mappings, SCHOOL_TYPE, SCHOOL_TABLE);
        return new ModelRegistry(types, new LinkedHashMap<>(), mappings, SOURCE_ID);
    }

    private static void register(Map<String, ObjectTypeDef> types,
                                 Map<String, ObjectMappingDef> mappings,
                                 String typeId, String table) {
        ObjectTypeDef type = new ObjectTypeDef();
        type.id = typeId;
        type.displayName = typeId;
        types.put(typeId, type);
        ObjectMappingDef mapping = new ObjectMappingDef();
        mapping.objectType = typeId;
        mapping.sourceTable = table;
        mapping.pk = "id";
        mappings.put(typeId, mapping);
    }

    private static SnapshotSource snapshotSource() {
        return new SnapshotSource() {
            @Override
            public long watermark() {
                return 85L;
            }

            @Override
            public boolean healthy() {
                return true;
            }

            @Override
            public LinkedHashMap<String, SnapshotBatch> snapshot(long revision) {
                return new LinkedHashMap<>();
            }
        };
    }

    /** 只读来源替身：999 号教室模拟「来源行已不存在」。 */
    private static final class FakeAdapter implements SourceAdapter {

        private final List<String> scans = new ArrayList<>();

        @Override
        public String sourceId() {
            return SOURCE_ID;
        }

        @Override
        public boolean healthCheck() {
            return true;
        }

        @Override
        public long currentWatermark() {
            return 85L;
        }

        @Override
        public List<SourceRecord> scan(SourceQuery query) {
            scans.add(query.objectType() + ":" + query.sourcePk());
            if ("999".equals(query.sourcePk())) {
                return List.of();
            }
            Map<String, Object> columns = new LinkedHashMap<>();
            columns.put("pk", query.sourcePk());
            return List.of(new SourceRecord(TABLE_OF_TYPE.getOrDefault(query.objectType(), SCHOOL_TABLE),
                    query.sourcePk(), columns, "hash"));
        }

        @Override
        public BusinessTime currentBusinessTime() {
            return null;
        }

        @Override
        public LookupResolver lookups() {
            return null;
        }

        List<String> scans() {
            return scans;
        }
    }

    /**
     * 快照构建替身：跳过 YAML/SPI，只产出「属性 + 一条关系 + 来源引用」，
     * 用来验证解析器是否正确地丢弃邻居批次的关系。
     */
    private static final class FakeBuilder extends SnapshotBuilder {

        FakeBuilder() {
            super(null, null);
        }

        @Override
        public SnapshotBatch buildAll(List<SourceRecord> records, String objectType,
                                      MappingContext ctx) {
            List<ProjectionRow> objects = new ArrayList<>();
            List<RelationInstance> relations = new ArrayList<>();
            List<SourceRefRow> refs = new ArrayList<>();
            for (SourceRecord record : records) {
                String globalId = GlobalIds.gid(ctx.sourceId(), objectType, record.pk());
                Map<String, Object> attributes = new LinkedHashMap<>(record.columns());
                attributes.put("refreshedAt", ctx.revision());
                objects.add(new ProjectionRow(globalId, ctx.sourceId(), objectType, record.pk(),
                        "标题-" + objectType + record.pk(), attributes, ctx.mappingVersion(),
                        ctx.revision(), false));
                relations.add(TestFixtures.relation("any_relation", globalId,
                        GlobalIds.gid(ctx.sourceId(), SCHOOL_TYPE, "1"), ctx.revision()));
                refs.add(TestFixtures.sourceRef(globalId, record.pk()));
            }
            return new SnapshotBatch(objects, relations, refs);
        }
    }
}
