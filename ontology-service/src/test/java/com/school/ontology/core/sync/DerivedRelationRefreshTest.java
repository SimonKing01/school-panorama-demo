package com.school.ontology.core.sync;

import com.school.ontology.core.model.GenerationRule;
import com.school.ontology.core.model.GlobalIds;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectMappingDef;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.model.RelationTypeDef;
import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.projection.SnapshotBuilder;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷回归 P8：调课（ADJUST）后 JOIN_RULE 派生关系不即时重算。
 *
 * <p>现象：一次调课把某课次的授课教师从甲换成乙后，课次的 4 条外键关系正确换边，
 * 但该班学生指向「当日任课教师」的派生关系仍是旧教师，必须等下一次全量导入/重建才补齐。
 * 结果同一业务事实在关系图 / 学生详情 / 关系列表三个视角下不一致。</p>
 *
 * <p>四类验收：</p>
 * <ol>
 *   <li>调课后学生的 JOIN_RULE 派生边换到新教师，旧边在该教师当天不再教这个班时结束；</li>
 *   <li>新路径**外键关系零影响**（邻居刷新不动任何 FK 边，也不产生假增删）；</li>
 *   <li>派生关系无变化时不产生假增删、不追加无变化的版本行；</li>
 *   <li>变更历史（{@link ChangeRecordRow}）里能看到派生边的增删。</li>
 * </ol>
 *
 * <p>全程不写死任何业务关系类型标识：类型名由本测试注册的定义给出，
 * 生产代码只通过 {@link ModelRegistry} 读 {@code generation.kind}。</p>
 */
class DerivedRelationRefreshTest {

    private static final String SOURCE_ID = TestFixtures.SOURCE_ID;

    private static final String STUDENT_TYPE = "student";
    private static final String TEACHER_TYPE = "teacher";
    private static final String LESSON_TYPE = "lessonSession";
    private static final String CLASS_TYPE = "class";

    private static final String STUDENT_TABLE = "students";
    private static final String TEACHER_TABLE = "teachers";
    private static final String LESSON_TABLE = "lesson_sessions";
    private static final String CLASS_TABLE = "classes";

    /** 派生关系（JOIN_RULE）：学生 → 当日任课教师。 */
    private static final String DERIVED_REL = "student_taught_by";
    /** 外键关系：学生 → 所属班级；外键关系绝不能被派生刷新路径碰到。 */
    private static final String STUDENT_CLASS_REL = "student_in_class";
    /** 外键关系：课次 → 授课教师。 */
    private static final String LESSON_TEACHER_REL = "session_taught_by";

    private static final String LESSON_PK = "3";
    private static final String STUDENT_1_PK = "1";
    private static final String STUDENT_2_PK = "2";
    private static final String TEACHER_OLD_PK = "5";
    private static final String TEACHER_NEW_PK = "8";
    private static final String CLASS_PK = "1";

    private final InMemoryProjectionStore store = new InMemoryProjectionStore();
    private final ProjectionWriter writer = new ProjectionWriter(store, registry());

    private final String lessonGid = gid(LESSON_TYPE, LESSON_PK);
    private final String student1Gid = gid(STUDENT_TYPE, STUDENT_1_PK);
    private final String student2Gid = gid(STUDENT_TYPE, STUDENT_2_PK);
    private final String teacherOldGid = gid(TEACHER_TYPE, TEACHER_OLD_PK);
    private final String teacherNewGid = gid(TEACHER_TYPE, TEACHER_NEW_PK);
    private final String classGid = gid(CLASS_TYPE, CLASS_PK);

    // ───────── 一、事件计划：派生关系走独立批次，属性批次继续不带关系 ─────────

    @Test
    void 事件计划把派生关系放进独立批次而属性批次仍不带关系() {
        FakeBuilder builder = new FakeBuilder(85, TEACHER_NEW_PK);
        EventPlan plan = new SourceDbEventSnapshotResolver(new FakeAdapter(), registry(),
                new ModelDefinitionLoader(), builder, snapshotSource()).plan(adjustEvent(85));

        // 属性刷新批次：学生 / 教师 / 班级各一份，relations() 必须继续为空
        //（否则 applyObjectRefresh 之外的人会误以为这些批次可以重算关系）。
        assertEquals(5, plan.refreshBatches().size(),
                "相关对象共 5 个（2 学生 + 2 教师 + 1 班级），来源都在，全部生成属性刷新批次");
        for (SnapshotBatch refresh : plan.refreshBatches()) {
            assertTrue(refresh.relations().isEmpty(),
                    "属性刷新批次不得携带任何关系（批次④ 阻断级回归的安全边界）");
        }

        // 派生关系批次：只有「声明了 JOIN_RULE 规则」的学生类型才有
        assertEquals(2, plan.derivedRefreshBatches().size(),
                "只有学生声明了派生关系规则，教师与班级不该产生派生批次");
        List<String> derivedGids = new ArrayList<>();
        for (SnapshotBatch batch : plan.derivedRefreshBatches()) {
            for (ProjectionRow row : batch.objects()) {
                derivedGids.add(row.globalId());
            }
            for (RelationInstance relation : batch.relations()) {
                assertEquals(DERIVED_REL, relation.relationType(),
                        "派生批次只允许携带 generation.kind == JOIN_RULE 的关系");
            }
        }
        assertTrue(derivedGids.containsAll(List.of(student1Gid, student2Gid)),
                "两名学生都要重算派生关系：" + derivedGids);

        // 学生的外键关系（学生→班级）必须被丢弃，绝不能混进派生批次。
        for (SnapshotBatch batch : plan.derivedRefreshBatches()) {
            for (RelationInstance relation : batch.relations()) {
                assertFalse(STUDENT_CLASS_REL.equals(relation.relationType()),
                        "外键关系不得进入派生批次");
            }
        }
        assertTrue(plan.tombstoneGlobalIds().isEmpty(), "来源行都在，不得误标墓碑");
        assertEquals(List.of(lessonGid), plan.selfGlobalIds(), "学生绝不能被标成 SELF_CHANGED");
    }

    // ───────── 二、端到端：调课后派生边换边，且外键关系零影响 ─────────

    @Test
    void 调课后学生派生边换到新教师且外键关系一条不动() {
        seedBeforeAdjust();

        EventPlan plan = new SourceDbEventSnapshotResolver(new FakeAdapter(), registry(),
                new ModelDefinitionLoader(), new FakeBuilder(85, TEACHER_NEW_PK),
                snapshotSource()).plan(adjustEvent(85));
        writer.applyEvent(adjustEvent(85), plan);

        // 学生的派生边已换到新教师，旧教师边真正结束。
        for (String studentGid : List.of(student1Gid, student2Gid)) {
            assertEquals(teacherNewGid, activeDerivedTargetOf(studentGid),
                    "学生派生边必须立刻指向新教师（增量 = 全量，无需等导入/重建）");
            assertFalse(isActive(derivedRelationId(studentGid, teacherOldGid)),
                    "该教师当天已不再教这个班，旧派生边必须结束");
            assertEquals(2, store.activeRelationsFrom(studentGid).size(),
                    "学生有效出边 = 1 条派生边 + 1 条外键边（学生→班级），外键边不得被误停");
            assertTrue(isActive(fkStudentClassRelationId(studentGid)),
                    "学生所在班级这条外键关系必须仍然有效");
        }
        // 课次自身的外键关系照旧由自身批次换边。
        assertTrue(isActive(fkLessonTeacherRelationId(teacherNewGid)));
        assertFalse(isActive(fkLessonTeacherRelationId(teacherOldGid)));
    }

    // ───────── 三、派生刷新路径本身：外键关系零影响（隔离验证） ─────────

    @Test
    void 派生关系刷新只结束派生类型边而不碰任何外键边() {
        seedObject(student1Gid, STUDENT_TYPE, STUDENT_1_PK, Map.of("name", "学生1"));
        seedObject(teacherOldGid, TEACHER_TYPE, TEACHER_OLD_PK, Map.of("name", "张明远"));
        seedObject(gid(TEACHER_TYPE, "9"), TEACHER_TYPE, "9", Map.of("name", "周文博"));
        seedObject(teacherNewGid, TEACHER_TYPE, TEACHER_NEW_PK, Map.of("name", "陈思琪"));
        seedObject(classGid, CLASS_TYPE, CLASS_PK, Map.of("name", "高一(1)班"));
        seedObject(gid(CLASS_TYPE, "2"), CLASS_TYPE, "2", Map.of("name", "高一(2)班"));

        // 学生身上：2 条派生边 + 2 条外键边（都指向不同的班级，模拟"同类型多条"的极端情况）。
        String oldDerived = derivedRelationId(student1Gid, teacherOldGid);
        String otherDerived = derivedRelationId(student1Gid, gid(TEACHER_TYPE, "9"));
        String classOneFk = fkStudentClassRelationId(student1Gid);
        String classTwoFk = TestFixtures.relation(STUDENT_CLASS_REL, student1Gid,
                gid(CLASS_TYPE, "2"), 84).relationId();
        store.upsertRelation(relation(DERIVED_REL, oldDerived, student1Gid, teacherOldGid, 84));
        store.upsertRelation(relation(DERIVED_REL, otherDerived, student1Gid,
                gid(TEACHER_TYPE, "9"), 84));
        store.upsertRelation(relation(STUDENT_CLASS_REL, classOneFk, student1Gid, classGid, 84));
        store.upsertRelation(relation(STUDENT_CLASS_REL, classTwoFk, student1Gid,
                gid(CLASS_TYPE, "2"), 84));

        // 只重算派生关系：新派生集合只有 1 条（新教师）。
        SnapshotBatch derivedBatch = new SnapshotBatch(
                List.of(row(STUDENT_TYPE, STUDENT_1_PK, 85, Map.of("name", "学生1"))),
                List.of(relation(DERIVED_REL, derivedRelationId(student1Gid, teacherNewGid),
                        student1Gid, teacherNewGid, 85)),
                new ArrayList<>());
        writer.applyDerivedRelationRefresh(derivedBatch, 85);

        assertFalse(isActive(oldDerived), "旧派生边必须结束");
        assertFalse(isActive(otherDerived), "不在保留清单里的派生边也必须结束");
        assertTrue(isActive(derivedRelationId(student1Gid, teacherNewGid)), "新派生边必须建立");
        assertTrue(isActive(classOneFk), "外键边（班级 1）零影响");
        assertTrue(isActive(classTwoFk), "外键边（班级 2）零影响");
        assertEquals(3, store.activeRelationsFrom(student1Gid).size(),
                "有效出边 = 1 派生 + 2 外键");
    }

    // ───────── 四、无变化不造假数据 ─────────

    @Test
    void 派生关系无变化时不产生假增删也不追加版本行() {
        seedBeforeAdjust();

        EventPlan first = new SourceDbEventSnapshotResolver(new FakeAdapter(), registry(),
                new ModelDefinitionLoader(), new FakeBuilder(85, TEACHER_NEW_PK),
                snapshotSource()).plan(adjustEvent(85));
        writer.applyEvent(adjustEvent(85), first);
        ChangeRecordRow firstChange = store.lastChange();
        assertEquals(3, firstChange.relationsAdded().size(),
                "首次调课真实新增：课次→新教师 + 2 名学生→新教师：" + firstChange.relationsAdded());
        assertEquals(3, firstChange.relationsRemoved().size(),
                "首次调课真实解除：课次→旧教师 + 2 名学生→旧教师：" + firstChange.relationsRemoved());

        long studentVersionsAfterFirst = versionCount(student1Gid);

        // 第二次事件：教师已经是 8，派生集合完全相同 —— 不得产生任何关系增删。
        EventPlan second = new SourceDbEventSnapshotResolver(new FakeAdapter(), registry(),
                new ModelDefinitionLoader(), new FakeBuilder(86, TEACHER_NEW_PK),
                snapshotSource()).plan(adjustEvent(86));
        writer.applyEvent(adjustEvent(86), second);
        ChangeRecordRow secondChange = store.lastChange();

        assertTrue(secondChange.relationsAdded().isEmpty(),
                "派生关系没变，不得产生假新增：" + secondChange.relationsAdded());
        assertTrue(secondChange.relationsRemoved().isEmpty(),
                "派生关系没变，不得产生假解除：" + secondChange.relationsRemoved());
        assertEquals(studentVersionsAfterFirst, versionCount(student1Gid),
                "学生属性没变，不得刷出无变化的版本行");
        assertEquals(2, store.activeRelationsFrom(student1Gid).size(),
                "学生的有效出边数量保持不变");
    }

    // ───────── 五、变更历史里能看到派生边的增删 ─────────

    @Test
    void 变更历史记录派生边的建立与结束() {
        seedBeforeAdjust();

        EventPlan plan = new SourceDbEventSnapshotResolver(new FakeAdapter(), registry(),
                new ModelDefinitionLoader(), new FakeBuilder(85, TEACHER_NEW_PK),
                snapshotSource()).plan(adjustEvent(85));
        writer.applyEvent(adjustEvent(85), plan);

        ChangeRecordRow change = store.lastChange();
        assertNotNull(change);
        assertTrue(hasEdge(change.relationsAdded(), student1Gid, teacherNewGid),
                "变更历史必须记录「学生1 → 新教师」的派生边建立：" + change.relationsAdded());
        assertTrue(hasEdge(change.relationsAdded(), student2Gid, teacherNewGid),
                "变更历史必须记录「学生2 → 新教师」的派生边建立：" + change.relationsAdded());
        assertTrue(hasEdge(change.relationsRemoved(), student1Gid, teacherOldGid),
                "变更历史必须记录「学生1 → 旧教师」的派生边结束：" + change.relationsRemoved());

        // 展示字段同样来自模型定义，不能出现裸 id。
        for (Map<String, Object> edge : change.relationsAdded()) {
            if (student1Gid.equals(edge.get("fromGlobalId"))) {
                assertEquals("学生-当日任课教师", edge.get("displayName"),
                        "关系展示名必须取模型定义");
                assertEquals(DERIVED_REL, edge.get("relationType"));
            }
        }
        // 外键关系一条没变，绝不能出现在差集里（否则就是假增删）。
        for (Map<String, Object> edge : change.relationsAdded()) {
            assertFalse(STUDENT_CLASS_REL.equals(edge.get("relationType")),
                    "外键关系未变化，不得出现在新增清单：" + edge);
        }
        for (Map<String, Object> edge : change.relationsRemoved()) {
            assertFalse(STUDENT_CLASS_REL.equals(edge.get("relationType")),
                    "外键关系未变化，不得出现在解除清单：" + edge);
        }
    }

    // ───────── 六、派生集合变空同样是真实变化 ─────────

    @Test
    void 派生集合变空时旧派生边必须结束() {
        seedBeforeAdjust();

        // 事件后该班当天再没有任何教师（联表规则算出空集）——派生批次里没有关系。
        EventPlan plan = new SourceDbEventSnapshotResolver(new FakeAdapter(), registry(),
                new ModelDefinitionLoader(), new FakeBuilder(85, null), snapshotSource())
                .plan(adjustEvent(85));
        writer.applyEvent(adjustEvent(85), plan);

        assertTrue(store.activeRelationsFrom(student1Gid).stream()
                        .noneMatch(relation -> DERIVED_REL.equals(relation.relationType())),
                "派生集合变空时，学生的派生边必须全部结束");
        assertTrue(isActive(fkStudentClassRelationId(student1Gid)),
                "派生边清空不得牵连外键边");
        assertTrue(hasEdge(store.lastChange().relationsRemoved(), student1Gid, teacherOldGid),
                "派生边结束必须进变更历史");
    }

    // ───────── 种子与断言辅助 ─────────

    /** 调课前的现场：课次 3 由旧教师 5 授课，两名学生各有 1 条指向旧教师的派生边。 */
    private void seedBeforeAdjust() {
        seedObject(lessonGid, LESSON_TYPE, LESSON_PK, Map.of("teacherName", "张明远"));
        seedObject(student1Gid, STUDENT_TYPE, STUDENT_1_PK, Map.of("name", "学生1"));
        seedObject(student2Gid, STUDENT_TYPE, STUDENT_2_PK, Map.of("name", "学生2"));
        seedObject(teacherOldGid, TEACHER_TYPE, TEACHER_OLD_PK, Map.of("name", "张明远"));
        seedObject(teacherNewGid, TEACHER_TYPE, TEACHER_NEW_PK, Map.of("name", "陈思琪"));
        seedObject(classGid, CLASS_TYPE, CLASS_PK, Map.of("name", "高一(1)班"));

        store.upsertRelation(relation(LESSON_TEACHER_REL, fkLessonTeacherRelationId(teacherOldGid),
                lessonGid, teacherOldGid, 84));
        for (String studentGid : List.of(student1Gid, student2Gid)) {
            store.upsertRelation(relation(DERIVED_REL, derivedRelationId(studentGid, teacherOldGid),
                    studentGid, teacherOldGid, 84));
            store.upsertRelation(relation(STUDENT_CLASS_REL, fkStudentClassRelationId(studentGid),
                    studentGid, classGid, 84));
        }
    }

    private void seedObject(String globalId, String objectType, String pk,
                            Map<String, Object> attributes) {
        store.upsertObject(new ProjectionRow(globalId, SOURCE_ID, objectType, pk,
                "标题-" + objectType + pk, new LinkedHashMap<>(attributes),
                TestFixtures.MAPPING_VERSION, 84L, false));
    }

    private static ProjectionRow row(String objectType, String pk, long revision,
                                     Map<String, Object> attributes) {
        return new ProjectionRow(gid(objectType, pk), SOURCE_ID, objectType, pk,
                "标题-" + objectType + pk, new LinkedHashMap<>(attributes),
                TestFixtures.MAPPING_VERSION, revision, false);
    }

    private static RelationInstance relation(String type, String relationId, String fromGid,
                                             String toGid, long revision) {
        return new RelationInstance(relationId, type, fromGid, toGid, "虚拟原因",
                "virtual_table", "1", "hash", TestFixtures.MAPPING_VERSION,
                null, null, true, revision);
    }

    private static String derivedRelationId(String fromGid, String toGid) {
        return TestFixtures.relation(DERIVED_REL, fromGid, toGid, 0L).relationId();
    }

    private static String fkStudentClassRelationId(String studentGid) {
        return TestFixtures.relation(STUDENT_CLASS_REL, studentGid,
                gid(CLASS_TYPE, CLASS_PK), 0L).relationId();
    }

    private static String fkLessonTeacherRelationId(String teacherGid) {
        return TestFixtures.relation(LESSON_TEACHER_REL, gid(LESSON_TYPE, LESSON_PK),
                teacherGid, 0L).relationId();
    }

    private static String gid(String objectType, String pk) {
        return GlobalIds.gid(SOURCE_ID, objectType, pk);
    }

    private boolean isActive(String relationId) {
        for (RelationInstance relation : store.relations()) {
            if (relation.relationId().equals(relationId)) {
                return relation.active();
            }
        }
        return false;
    }

    private String activeDerivedTargetOf(String fromGid) {
        for (RelationInstance relation : store.activeRelationsFrom(fromGid)) {
            if (DERIVED_REL.equals(relation.relationType())) {
                return relation.toGlobalId();
            }
        }
        return null;
    }

    private long versionCount(String globalId) {
        return store.objectVersions().stream()
                .filter(version -> globalId.equals(version.get("globalId")))
                .count();
    }

    private static boolean hasEdge(List<Map<String, Object>> edges, String fromGid, String toGid) {
        for (Map<String, Object> edge : edges) {
            if (fromGid.equals(edge.get("fromGlobalId")) && toGid.equals(edge.get("toGlobalId"))) {
                return true;
            }
        }
        return false;
    }

    // ───────── 事件与替身 ─────────

    /** 复现业务侧 payload：items 只给自身对象（课次 3），impact 给出该班学生与新旧教师。 */
    private static OutboxEvent adjustEvent(long revision) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceTable", LESSON_TABLE);
        item.put("sourcePk", LESSON_PK);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("studentIds", List.of(1, 2));
        impact.put("teacherIds", List.of(5, 8));
        impact.put("classIds", List.of(1));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", List.of(item));
        payload.put("impact", impact);
        return new OutboxEvent("evt-adjust-" + revision, null, "LESSON_SESSION", LESSON_PK,
                "ADJUST", revision, payload, LocalDateTime.of(2026, 11, 14, 10, 15),
                LocalDateTime.of(2026, 11, 14, 11, 0), "PENDING", 0);
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

    /** 只读来源替身：任何主键都能扫到一行（本用例不验证「邻居缺失」分支）。 */
    private static final class FakeAdapter implements SourceAdapter {

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
            Map<String, Object> columns = new LinkedHashMap<>();
            columns.put("pk", query.sourcePk());
            return List.of(new SourceRecord(TABLE_OF_TYPE.getOrDefault(query.objectType(), LESSON_TABLE),
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
    }

    private static final Map<String, String> TABLE_OF_TYPE = Map.of(
            STUDENT_TYPE, STUDENT_TABLE,
            TEACHER_TYPE, TEACHER_TABLE,
            LESSON_TYPE, LESSON_TABLE,
            CLASS_TYPE, CLASS_TABLE);

    /**
     * 快照构建替身：按「当前授课教师」派生关系，模拟调课前后来源库的真实状态。
     *
     * <p>学生：属性 + 1 条 JOIN_RULE 派生边（→ 当前授课教师）+ 1 条外键边（→ 所在班级）；
     * 教师 / 班级：只有属性。这样一眼能看出解析器是否只把派生边留下。</p>
     */
    private static final class FakeBuilder extends SnapshotBuilder {

        private final long revision;
        private final String currentTeacherPk;

        FakeBuilder(long revision, String currentTeacherPk) {
            super(null, null);
            this.revision = revision;
            this.currentTeacherPk = currentTeacherPk;
        }

        @Override
        public SnapshotBatch buildAll(List<SourceRecord> records, String objectType,
                                      MappingContext ctx) {
            List<ProjectionRow> objects = new ArrayList<>();
            List<RelationInstance> relations = new ArrayList<>();
            for (SourceRecord record : records) {
                String globalId = GlobalIds.gid(ctx.sourceId(), objectType, record.pk());
                Map<String, Object> attributes = new LinkedHashMap<>(record.columns());
                objects.add(new ProjectionRow(globalId, ctx.sourceId(), objectType, record.pk(),
                        "标题-" + objectType + record.pk(), attributes, ctx.mappingVersion(),
                        revision, false));
                if (STUDENT_TYPE.equals(objectType) && currentTeacherPk != null) {
                    relations.add(relation(DERIVED_REL,
                            derivedRelationId(globalId, gid(TEACHER_TYPE, currentTeacherPk)),
                            globalId, gid(TEACHER_TYPE, currentTeacherPk), revision));
                }
                if (STUDENT_TYPE.equals(objectType)) {
                    relations.add(relation(STUDENT_CLASS_REL,
                            fkStudentClassRelationId(globalId), globalId,
                            gid(CLASS_TYPE, CLASS_PK), revision));
                }
                // 课次的外键关系同样由「当前授课教师」决定：它是「自身批次」的职责，
                // 换边必须落在这里，与学生的派生边互不干扰。
                if (LESSON_TYPE.equals(objectType) && currentTeacherPk != null) {
                    relations.add(relation(LESSON_TEACHER_REL,
                            fkLessonTeacherRelationId(gid(TEACHER_TYPE, currentTeacherPk)),
                            globalId, gid(TEACHER_TYPE, currentTeacherPk), revision));
                }
            }
            List<com.school.ontology.core.projection.SourceRefRow> refs = new ArrayList<>();
            for (ProjectionRow row : objects) {
                refs.add(TestFixtures.sourceRef(row.globalId(), row.sourcePk()));
            }
            return new SnapshotBatch(objects, relations, refs);
        }
    }

    // ───────── 测试专用模型定义（类型名由本测试声明，生产代码不感知） ─────────

    private static ModelRegistry registry() {
        Map<String, ObjectTypeDef> types = new LinkedHashMap<>();
        Map<String, ObjectMappingDef> mappings = new LinkedHashMap<>();
        Map<String, RelationTypeDef> relations = new LinkedHashMap<>();

        registerType(types, mappings, STUDENT_TYPE, STUDENT_TABLE);
        registerType(types, mappings, TEACHER_TYPE, TEACHER_TABLE);
        registerType(types, mappings, LESSON_TYPE, LESSON_TABLE);
        registerType(types, mappings, CLASS_TYPE, CLASS_TABLE);

        relations.put(DERIVED_REL, relationDef(DERIVED_REL, "学生-当日任课教师",
                STUDENT_TYPE, TEACHER_TYPE, ModelRegistry.DERIVED_GENERATION_KIND));
        relations.put(STUDENT_CLASS_REL, relationDef(STUDENT_CLASS_REL, "学生-所属班级",
                STUDENT_TYPE, CLASS_TYPE, "FOREIGN_KEY"));
        relations.put(LESSON_TEACHER_REL, relationDef(LESSON_TEACHER_REL, "课次-授课教师",
                LESSON_TYPE, TEACHER_TYPE, "FOREIGN_KEY"));

        // 派生关系只在学生的映射里声明（与生产定义同构：只有 student 有 joinRelationRules）。
        ObjectMappingDef.JoinRelationRule joinRule = new ObjectMappingDef.JoinRelationRule();
        joinRule.relationType = DERIVED_REL;
        joinRule.rule = "student.class_id == lessonSession.class_id → lessonSession.teacher_id";
        joinRule.distinct = true;
        joinRule.sourceColumn = "class_id";
        joinRule.joinTable = LESSON_TABLE;
        joinRule.joinFilterColumn = "class_id";
        joinRule.joinSelectColumn = "teacher_id";
        joinRule.targetObjectType = TEACHER_TYPE;
        mappings.get(STUDENT_TYPE).joinRelationRules.add(joinRule);

        ObjectMappingDef.RelationRuleDef studentClassRule = new ObjectMappingDef.RelationRuleDef();
        studentClassRule.relationType = STUDENT_CLASS_REL;
        studentClassRule.fk = "class_id";
        studentClassRule.targetObjectType = CLASS_TYPE;
        mappings.get(STUDENT_TYPE).relationRules.add(studentClassRule);

        ObjectMappingDef.RelationRuleDef lessonTeacherRule = new ObjectMappingDef.RelationRuleDef();
        lessonTeacherRule.relationType = LESSON_TEACHER_REL;
        lessonTeacherRule.fk = "teacher_id";
        lessonTeacherRule.targetObjectType = TEACHER_TYPE;
        mappings.get(LESSON_TYPE).relationRules.add(lessonTeacherRule);

        return new ModelRegistry(types, relations, mappings, SOURCE_ID);
    }

    private static RelationTypeDef relationDef(String id, String displayName, String fromType,
                                               String toType, String kind) {
        RelationTypeDef def = new RelationTypeDef();
        def.id = id;
        def.displayName = displayName;
        def.fromType = fromType;
        def.toType = toType;
        def.direction = "OUT";
        def.cardinality = "N:M";
        def.generation = new GenerationRule();
        def.generation.kind = kind;
        return def;
    }

    private static void registerType(Map<String, ObjectTypeDef> types,
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
}
