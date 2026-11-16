package com.school.ontology.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.ontology.repository.OntologyRepository;
import com.school.ontology.support.InMemoryVersionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6 缺陷回归：「保留版本与操作人」。
 *
 * <p>缺陷现象：保存时明明传了 {@code operator=主理人复核, note=复核编辑链路}，
 * 但 {@code ont_mapping_version} 里那一版的 operator/note 变成
 * {@code system / startup-overlay}，{@code ont_model_def} 里也没有 overlay 生效时的内容。</p>
 *
 * <p>本类用一份与 SQL 语义一致的内存版本表覆盖五条修法：</p>
 * <ol>
 *   <li>保存即登记 PENDING（带 operator/note），同一草稿重复保存仍只有一行；</li>
 *   <li>启动激活把 PENDING 原地转 ACTIVE 且保留人名，{@code MappingVersionRecorder}
 *       再跑一遍也不许覆盖成 {@code system / startup-overlay}；</li>
 *   <li>纯基线首启仍然记 {@code system / startup-baseline}；</li>
 *   <li>「恢复基线 → 应用」之后，基线那条 ACTIVE 的 operator 是执行恢复的人；</li>
 *   <li>{@code ont_model_def} 按版本留存完整关系定义内容（10 条齐全）。</li>
 * </ol>
 */
class DefinitionSignaturePersistenceTest {

    private static final String OPERATOR = "寇豆码";

    @TempDir
    Path directory;

    private DefinitionFileStore store;
    private InMemoryVersionStore table;
    private OntologyRepository repository;
    private String sourceId;
    private String baselineVersion;

    @BeforeEach
    void setUp() {
        store = new DefinitionFileStore(directory);
        table = new InMemoryVersionStore();
        repository = table.bind();
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        sourceId = baseline.registry().sourceId();
        baselineVersion = baseline.mappingVersion().mappingVersion;
    }

    @Test
    void 保存草稿立刻登记一行PENDING且署名是操作人_重复保存仍只有一行() {
        seedActiveBaseline();
        ModelDefinitionLoader loader = new ModelDefinitionLoader(store);
        RelationTypeDefinitionEditor editor = editor(loader);

        Map<String, Object> saved = editor.add(request(loader, OPERATOR, "署名验证",
                validDefinition("teacher_mentor")));
        String draftVersion = String.valueOf(saved.get("pendingMappingVersion"));
        assertNotEquals(baselineVersion, draftVersion, "改了定义就必须换版本");

        Map<String, Object> row = table.version(sourceId, draftVersion);
        assertEquals("PENDING", row.get("status"), "保存只登记待生效，不能直接算生效");
        assertEquals(OPERATOR, row.get("operator"), "库里必须留下人手写的 operator");
        assertEquals("署名验证", row.get("note"), "库里必须留下人手写的 note");
        assertEquals("OVERLAY", row.get("definition_source"));
        assertEquals(1, table.versionsWithStatus("PENDING").size(), "一次保存只应有一行 PENDING");

        // 同一草稿再保存一次：版本号不变 → 覆盖同一行，不堆 PENDING。
        editor.update("teacher_mentor", request(loader, OPERATOR, "署名验证",
                validDefinition("teacher_mentor")));
        assertEquals(1, table.versionsWithStatus("PENDING").size(),
                "同一草稿重复保存不能堆出一堆 PENDING");
        assertEquals(2, table.allVersions().size(), "基线 ACTIVE + 一行 PENDING");

        // 换个人再保存同一份草稿：署名应该变成最新这个人。
        editor.update("teacher_mentor", request(loader, "张三", "复核后再存",
                validDefinition("teacher_mentor")));
        Map<String, Object> overwritten = table.version(sourceId, draftVersion);
        assertEquals("张三", overwritten.get("operator"), "同一行被覆盖时署名取最新操作人");
        assertEquals("复核后再存", overwritten.get("note"));
    }

    @Test
    void 启动激活把PENDING原地转ACTIVE并保留人名() {
        seedActiveBaseline();
        ModelDefinitionLoader loader = new ModelDefinitionLoader(store);
        String draftVersion = String.valueOf(editor(loader)
                .add(request(loader, OPERATOR, "署名验证", validDefinition("teacher_mentor")))
                .get("pendingMappingVersion"));

        // 模拟「重启」：新进程用同一个定义目录构造 loader，读到刚写的 overlay。
        ModelDefinitionLoader restarted = new ModelDefinitionLoader(store);
        assertEquals("OVERLAY", restarted.definitionSource());
        assertEquals(draftVersion, restarted.mappingVersion().mappingVersion,
                "草稿哈希必须与登记时的版本号一致，否则激活时对不上");

        DefinitionActivationRunner runner = new DefinitionActivationRunner(restarted, repository, () -> {
        });
        runner.run(null);
        assertTrue(runner.needRebuild(), "定义变了必须重建当前投影");

        Map<String, Object> row = table.version(sourceId, draftVersion);
        assertEquals("ACTIVE", row.get("status"), "PENDING 必须原地转 ACTIVE");
        assertEquals(OPERATOR, row.get("operator"), "激活绝不能把人写的 operator 冲掉");
        assertEquals("署名验证", row.get("note"));
        assertEquals("OVERLAY", row.get("definition_source"));
        assertEquals("SUPERSEDED", table.version(sourceId, baselineVersion).get("status"),
                "旧生效版本要让位");

        // 关键回归点：@Order(20) 的登记器随后还会 upsert 一次，过去正是它写成 system/startup-overlay。
        new MappingVersionRecorder(restarted, repository).run(null);
        Map<String, Object> afterRecorder = table.version(sourceId, draftVersion);
        assertEquals("ACTIVE", afterRecorder.get("status"));
        assertEquals(OPERATOR, afterRecorder.get("operator"),
                "禁止用 system / startup-overlay 覆盖人写的 operator");
        assertEquals("署名验证", afterRecorder.get("note"),
                "禁止用 system / startup-overlay 覆盖人写的 note");
    }

    @Test
    void 纯基线首启仍然记system与startup_baseline() {
        ModelDefinitionLoader loader = new ModelDefinitionLoader(store);

        new DefinitionActivationRunner(loader, repository, () -> {
        }).run(null);
        Map<String, Object> row = table.version(sourceId, baselineVersion);
        assertEquals("ACTIVE", row.get("status"));
        assertEquals("system", row.get("operator"), "没有任何人参与的首启才允许记 system");
        assertEquals("startup-baseline", row.get("note"));
        assertEquals("BASELINE", row.get("definition_source"));

        new MappingVersionRecorder(loader, repository).run(null);
        assertEquals("startup-baseline", table.version(sourceId, baselineVersion).get("note"),
                "登记器第二次跑要保持同一份署名，不能写出第二个 note");
    }

    @Test
    void 恢复基线并应用之后生效版本记在执行恢复的人头上() {
        seedActiveBaseline();
        // 1) 先真的改一版并激活（寇豆码）
        ModelDefinitionLoader editing = new ModelDefinitionLoader(store);
        String draftVersion = String.valueOf(editor(editing)
                .add(request(editing, OPERATOR, "署名验证", validDefinition("teacher_mentor")))
                .get("pendingMappingVersion"));
        ModelDefinitionLoader overlayBoot = new ModelDefinitionLoader(store);
        new DefinitionActivationRunner(overlayBoot, repository, () -> {
        }).run(null);
        assertEquals("ACTIVE", table.version(sourceId, draftVersion).get("status"));

        // 2) 执行恢复基线：执行人是寇豆码，还得带自己的备注
        RelationEditRequest reset = new RelationEditRequest();
        reset.baseMappingVersion = draftVersion;
        reset.confirm = true;
        reset.operator = OPERATOR;
        reset.note = "恢复基线";
        editor(overlayBoot).reset(reset);
        assertFalse(store.overlayExists());
        assertEquals("PENDING", table.version(sourceId, baselineVersion).get("status"),
                "恢复基线要登记一条待生效（目标=基线版本）");
        assertEquals(OPERATOR, table.version(sourceId, baselineVersion).get("operator"));

        // 3) 应用后重启：生效版本必须是基线，且署名是执行恢复的人
        ModelDefinitionLoader baselineBoot = new ModelDefinitionLoader(store);
        assertEquals("BASELINE", baselineBoot.definitionSource());
        new DefinitionActivationRunner(baselineBoot, repository, () -> {
        }).run(null);
        new MappingVersionRecorder(baselineBoot, repository).run(null);

        Map<String, Object> row = table.version(sourceId, baselineVersion);
        assertEquals("ACTIVE", row.get("status"), "回到基线后这一行必须重新生效");
        assertEquals(OPERATOR, row.get("operator"), "不能退回 system");
        assertEquals("恢复基线", row.get("note"));
        assertEquals("BASELINE", row.get("definition_source"));
        assertEquals("SUPERSEDED", table.version(sourceId, draftVersion).get("status"));
    }

    @Test
    void 每个版本都能按版本号查到当时完整的关系定义内容() throws Exception {
        seedActiveBaseline();
        ModelDefinitionLoader editing = new ModelDefinitionLoader(store);
        String draftVersion = String.valueOf(editor(editing)
                .add(request(editing, OPERATOR, "署名验证", validDefinition("teacher_mentor")))
                .get("pendingMappingVersion"));

        ModelDefinitionLoader overlayBoot = new ModelDefinitionLoader(store);
        new DefinitionActivationRunner(overlayBoot, repository, () -> {
        }).run(null);
        new MappingVersionRecorder(overlayBoot, repository).run(null);

        String defKey = MappingVersionRecorder.versionedKey("relation-types", draftVersion);
        Map<String, Object> def = table.modelDef(defKey);
        assertFalse(def.isEmpty(), "必须按版本留存一份定义内容，实际已有：" + table.modelDefKeys());
        assertEquals(draftVersion, def.get("mapping_version"));
        assertEquals(OPERATOR, def.get("operator"), "定义内容的署名要与版本行一致");
        assertEquals("署名验证", def.get("note"));
        assertEquals("OVERLAY", def.get("definition_source"));

        JsonNode node = new ObjectMapper().valueToTree(def.get("content"));
        JsonNode relations = node.get("relationTypes");
        assertNotNull(relations, "content 必须是完整的关系定义文档");
        assertEquals(11, relations.size(), "基线 10 条 + 本次新增 1 条，一条都不能丢");
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (JsonNode relation : relations) {
            ids.add(relation.path("id").asText());
        }
        for (RelationTypeDef baselineDef : new ModelDefinitionLoader().registry().relations()) {
            ids.remove(baselineDef.id);
        }
        assertEquals(java.util.Set.of("teacher_mentor"), ids,
                "内容不能被裁剪成只剩改动项：基线 10 条必须齐全，只多出本次新增的那条");

        // 基线的历史快照也要在，否则「按版本回看」只能看到最后一版。
        String baselineKey = MappingVersionRecorder.versionedKey("relation-types", baselineVersion);
        assertFalse(table.modelDef(baselineKey).isEmpty(), "基线版本也要留一份快照");
        // 既有的「当前内容」三行保持可用（模型管理页 / 来源追溯依赖它）。
        assertFalse(table.modelDef("relation-types").isEmpty(), "既有 def_key=relation-types 不能被移除");
        assertEquals(draftVersion, table.modelDef("relation-types").get("mapping_version"));
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    /**
     * 造出「上一轮跑的是打包基线」的历史：一条 ACTIVE 基线版本行 + 该版本的定义内容快照。
     * 直接走真实的 {@link MappingVersionRecorder}，避免测试替身和实现漂移。
     */
    private void seedActiveBaseline() {
        new MappingVersionRecorder(new ModelDefinitionLoader(), repository).run(null);
        assertEquals("ACTIVE", table.version(sourceId, baselineVersion).get("status"));
    }

    private RelationTypeDefinitionEditor editor(ModelDefinitionLoader loader) {
        return new RelationTypeDefinitionEditor(loader,
                new RelationTypeValidator(loader, loader.registry()), store, repository);
    }

    private RelationEditRequest request(ModelDefinitionLoader loader, String operator, String note,
                                        RelationTypeDef def) {
        RelationEditRequest request = new RelationEditRequest();
        request.baseMappingVersion = loader.mappingVersion().mappingVersion;
        request.operator = operator;
        request.note = note;
        request.relation = def;
        return request;
    }

    private static RelationTypeDef validDefinition(String id) {
        RelationTypeDef def = new RelationTypeDef();
        def.id = id;
        def.displayName = "学生-导师（署名回归）";
        def.fromType = "student";
        def.toType = "teacher";
        def.forwardName = "导师";
        def.reverseName = "学生";
        def.direction = "OUT";
        def.cardinality = "N:1";
        def.businessEffectiveTime = false;
        def.sourceMappingRef = "school-source.yaml#student";
        GenerationRule generation = new GenerationRule();
        generation.kind = "FOREIGN_KEY";
        generation.sourceTable = "students";
        generation.fkColumn = "teacher_id";
        generation.targetTable = "teachers";
        def.generation = generation;
        return def;
    }
}
