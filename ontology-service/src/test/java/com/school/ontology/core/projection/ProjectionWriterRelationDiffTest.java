package com.school.ontology.core.projection;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.model.RelationTypeDef;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.support.InMemoryProjectionStore;
import com.school.ontology.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷回归：变更历史的「关系增加 / 解除」必须由底座在落库时从投影真实前后状态算出，
 * 而不是依赖来源系统上报（原业务 Outbox 负载里没有 relationsAdded/relationsRemoved）。
 *
 * <p>断言三项：换边时「旧关系解除 + 新关系建立」各 1 条、展示名来自模型定义、
 * 墓碑时出入边都进入「已解除关系」（验收三⑤「对象删除后有效关系清理」的可解释证据）。</p>
 */
class ProjectionWriterRelationDiffTest {

    private static final String LESSON_TYPE = "lessonSession";
    private static final String TEACHER_TYPE = "teacher";
    private static final String STUDENT_TYPE = "student";
    private static final String TAUGHT_BY = "session_taught_by";
    private static final String VIEWED_BY = "session_viewed_by";

    private final InMemoryProjectionStore store = new InMemoryProjectionStore();
    private final ProjectionWriter writer = new ProjectionWriter(store, registry());

    private final String lessonGid = TestFixtures.gid(LESSON_TYPE, "3");
    private final String oldTeacherGid = TestFixtures.gid(TEACHER_TYPE, "5");
    private final String newTeacherGid = TestFixtures.gid(TEACHER_TYPE, "7");
    private final String studentGid = TestFixtures.gid(STUDENT_TYPE, "1");

    /** 关系展示名必须来自模型定义，因此这里给测试注册表补上真实的关系定义。 */
    private static ModelRegistry registry() {
        Map<String, ObjectTypeDef> types = new LinkedHashMap<>();
        for (String id : List.of(LESSON_TYPE, TEACHER_TYPE, STUDENT_TYPE)) {
            ObjectTypeDef def = new ObjectTypeDef();
            def.id = id;
            def.displayName = id;
            types.put(id, def);
        }
        Map<String, RelationTypeDef> relations = new LinkedHashMap<>();
        relations.put(TAUGHT_BY, relationDef(TAUGHT_BY, "授课安排-授课教师", LESSON_TYPE, TEACHER_TYPE));
        relations.put(VIEWED_BY, relationDef(VIEWED_BY, "学生-可查看安排", STUDENT_TYPE, LESSON_TYPE));
        return new ModelRegistry(types, relations, new LinkedHashMap<>(), TestFixtures.SOURCE_ID);
    }

    private static RelationTypeDef relationDef(String id, String displayName,
                                               String fromType, String toType) {
        RelationTypeDef def = new RelationTypeDef();
        def.id = id;
        def.displayName = displayName;
        def.fromType = fromType;
        def.toType = toType;
        def.cardinality = "N:1";
        def.direction = "OUT";
        return def;
    }

    private void seed(String globalId, String objectType, String pk, long revision) {
        store.upsertObject(new ProjectionRow(globalId, TestFixtures.SOURCE_ID, objectType, pk,
                "标题-" + objectType + pk, new LinkedHashMap<>(Map.of("name", objectType + pk)),
                TestFixtures.MAPPING_VERSION, revision, false));
    }

    private ProjectionRow lessonRow(long revision, String teacherName) {
        return new ProjectionRow(lessonGid, TestFixtures.SOURCE_ID, LESSON_TYPE, "3",
                "标题-授课安排3", new LinkedHashMap<>(Map.of("teacherName", teacherName)),
                TestFixtures.MAPPING_VERSION, revision, false);
    }

    /** 一次「授课教师 = target」的事件计划（批内只有授课安排对象 + 一条外键关系）。 */
    private EventPlan plan(ProjectionRow row, String targetTeacherGid, long revision) {
        RelationInstance relation = TestFixtures.relation(TAUGHT_BY, row.globalId(),
                targetTeacherGid, revision);
        return new EventPlan(List.of(TestFixtures.batch(row, relation)), List.of(), Map.of(),
                List.of(row.globalId()), List.of(),
                Map.of("changeKind", "ADJUST", "reason", "教师替换"), List.of(), List.of());
    }

    @Test
    void 关系换边时变更历史记录解除与新增() {
        seed(lessonGid, LESSON_TYPE, "3", 84);
        seed(oldTeacherGid, TEACHER_TYPE, "5", 84);
        seed(newTeacherGid, TEACHER_TYPE, "7", 84);

        writer.applyEvent(TestFixtures.event("evt-1", 85, "ADJUST"),
                plan(lessonRow(85, "张明远"), oldTeacherGid, 85));
        ChangeRecordRow established = store.lastChange();
        assertEquals(1, established.relationsAdded().size(), "首次建立关系必须被记录");
        assertEquals(0, established.relationsRemoved().size());
        assertEquals(TAUGHT_BY, established.relationsAdded().get(0).get("relationType"));
        assertEquals("授课安排-授课教师", established.relationsAdded().get(0).get("displayName"),
                "展示名必须来自模型定义");
        assertEquals("标题-teacher5", established.relationsAdded().get(0).get("toTitle"),
                "关系两端标题必须从投影补齐");

        writer.applyEvent(TestFixtures.event("evt-2", 86, "ADJUST"),
                plan(lessonRow(86, "陈思琪"), newTeacherGid, 86));
        ChangeRecordRow replaced = store.lastChange();

        assertEquals(1, replaced.relationsRemoved().size(), "旧教师关系必须出现在「已结束关系」");
        Map<String, Object> removed = replaced.relationsRemoved().get(0);
        assertEquals(TAUGHT_BY, removed.get("relationType"));
        assertEquals("授课安排-授课教师", removed.get("displayName"));
        assertEquals(lessonGid, removed.get("fromGlobalId"));
        assertEquals("标题-授课安排3", removed.get("fromTitle"));
        assertEquals(oldTeacherGid, removed.get("toGlobalId"));
        assertEquals("标题-teacher5", removed.get("toTitle"));

        assertEquals(1, replaced.relationsAdded().size(), "新教师关系必须出现在「新建立关系」");
        Map<String, Object> added = replaced.relationsAdded().get(0);
        assertEquals(newTeacherGid, added.get("toGlobalId"));
        assertEquals("标题-teacher7", added.get("toTitle"));
        assertFalse(replaced.relationsAdded().get(0).get("relationId")
                .equals(removed.get("relationId")), "新旧关系必须是不同的关系实例");

        // 投影侧一致性：该对象当前只剩一条有效出边（新教师）。
        assertEquals(1, store.activeRelationsFrom(lessonGid).size());
        assertEquals(newTeacherGid, store.activeRelationsFrom(lessonGid).get(0).toGlobalId());
    }

    @Test
    void 对象删除时出入边都进入已解除关系() {
        seed(lessonGid, LESSON_TYPE, "3", 84);
        seed(oldTeacherGid, TEACHER_TYPE, "5", 84);
        writer.applyEvent(TestFixtures.event("evt-1", 85, "ADJUST"),
                plan(lessonRow(85, "张明远"), oldTeacherGid, 85));
        // 再建立一条入边（学生 → 该授课安排），验证墓碑不是只看「出边」。
        store.upsertRelation(TestFixtures.relation(VIEWED_BY, studentGid, lessonGid, 85));

        EventPlan tombstonePlan = new EventPlan(List.of(), List.of(lessonGid), Map.of(),
                List.of(lessonGid), List.of(), Map.of("changeKind", "DELETE", "reason", "排课被删除"),
                List.of(), List.of());
        writer.applyEvent(TestFixtures.event("evt-2", 86, "DELETE"), tombstonePlan);

        ChangeRecordRow change = store.lastChange();
        assertEquals(0, change.relationsAdded().size());
        assertEquals(2, change.relationsRemoved().size(), "出边与入边都应进入「已结束关系」");
        assertTrue(change.relationsRemoved().stream()
                        .anyMatch(entry -> oldTeacherGid.equals(entry.get("toGlobalId"))),
                "出边（授课安排 → 教师）必须被记录为已解除");
        assertTrue(change.relationsRemoved().stream()
                        .anyMatch(entry -> studentGid.equals(entry.get("fromGlobalId"))),
                "入边（学生 → 授课安排）也必须被记录为已解除");

        assertTrue(store.activeRelationsFrom(lessonGid).isEmpty());
        assertTrue(store.activeRelationsTo(lessonGid).isEmpty());
    }
}
