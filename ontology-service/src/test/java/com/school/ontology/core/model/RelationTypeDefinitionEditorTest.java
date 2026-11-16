package com.school.ontology.core.model;

import com.school.ontology.common.ApiException;
import com.school.ontology.repository.OntologyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 关系类型编辑编排（07 增量设计 §6）：增/改/删/恢复基线 + 版本登记 + 闸门。 */
class RelationTypeDefinitionEditorTest {

    @TempDir
    Path directory;

    private ModelDefinitionLoader loader;
    private DefinitionFileStore store;
    private OntologyRepository repository;
    private RelationTypeDefinitionEditor editor;
    private String baseVersion;

    @BeforeEach
    void setUp() {
        loader = new ModelDefinitionLoader();
        store = new DefinitionFileStore(directory);
        repository = mock(OntologyRepository.class);
        baseVersion = loader.mappingVersion().mappingVersion;
        when(repository.activeMappingVersion(anyString()))
                .thenReturn(Map.<String, Object>of("mapping_version", baseVersion));
        when(repository.hasRunningTask(anyString())).thenReturn(false);
        editor = new RelationTypeDefinitionEditor(loader,
                new RelationTypeValidator(loader, loader.registry()), store, repository);
    }

    @Test
    void 新增关系保存为草稿并登记PENDING版本() {
        Map<String, Object> result = editor.add(request(validDefinition("teacher_mentor")));

        assertEquals(Boolean.TRUE, result.get("saved"));
        assertEquals(Boolean.FALSE, result.get("applied"));
        String pending = String.valueOf(result.get("pendingMappingVersion"));
        assertTrue(pending.startsWith("school-panorama:"), "实际：" + pending);
        assertNotEquals(baseVersion, pending, "改了定义就必须换版本");
        assertTrue(store.overlayExists(), "草稿必须落盘到 overlay");
        assertEquals("OVERLAY", result.get("definitionSource"));
        verify(repository).markPendingMappingVersion(eq("school-panorama"), eq(pending), anyString(),
                eq(RelationTypeDefinitionEditor.DEFAULT_OPERATOR),
                eq("新增关系类型 teacher_mentor"), eq("OVERLAY"));
        verify(repository).supersedeOtherPending("school-panorama", pending);
    }

    @Test
    void 字段非法时逐条返回中文错误且不写文件() {
        RelationTypeDef def = validDefinition("1-bad id");
        def.fromType = "nope";
        def.displayName = null;
        def.generation.fkColumn = null;

        ApiException error = assertThrows(ApiException.class, () -> editor.add(request(def)));

        assertEquals(400, error.httpStatus);
        assertEquals("VALIDATION_FAILED", error.code);
        assertFalse(store.overlayExists(), "校验失败绝不能写 overlay");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) error.data;
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) data.get("errors");
        assertTrue(errors.size() >= 3, "应逐条返回，实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("关系类型 id 非法")), "实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("起点类型不存在：nope")), "实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("displayName 不能为空")), "实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("FOREIGN_KEY 缺少 fkColumn")), "实际：" + errors);
    }

    @Test
    void 修改展示名后草稿内容变化且版本变化() {
        RelationTypeDef def = copyOf(loader.registry().relationOf("session_taught_by"));
        def.displayName = "授课教师（在线）";

        Map<String, Object> result = editor.update("session_taught_by", request(def));

        assertTrue(String.valueOf(result.get("pendingMappingVersion")).startsWith("school-panorama:"));
        assertNotEquals(baseVersion, result.get("pendingMappingVersion"));
        assertTrue(store.readOverlayText().contains("授课教师（在线）"), "草稿必须包含新展示名");
        assertTrue(store.readOverlayText().contains("classroom_in_school"),
                "草稿必须是完整定义（否则会丢掉教室关系）");
    }

    @Test
    void 同一进程内可以继续编辑刚保存的草稿() {
        editor.add(request(validDefinition("teacher_mentor")));
        String firstDraft = editor.draftVersion("school-panorama");
        assertNotNull(firstDraft);

        // 关键点：新关系不在 ModelRegistry 里（要重启才生效），但第二次编辑必须能看到它。
        RelationTypeDef def = copyOf(validDefinition("teacher_mentor"));
        def.displayName = "导师（第二次编辑）";
        editor.update("teacher_mentor", request(def));

        assertTrue(store.readOverlayText().contains("导师（第二次编辑）"));
        assertNotEquals(firstDraft, editor.draftVersion("school-panorama"), "再改一次版本应再变");
        assertEquals(11, countRelationsInDraft(), "不能因为二次编辑把关系条数改错");
    }

    @Test
    void 删除被映射引用的关系先返回影响清单() {
        ApiException error = assertThrows(ApiException.class,
                () -> editor.delete("student_taught_by", request(null)));

        assertEquals(409, error.httpStatus);
        assertEquals("DELETE_IMPACT_CONFIRM", error.code);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) error.data;
        assertEquals("student_taught_by", data.get("relationId"));
        assertFalse(((List<?>) data.get("referencedByMappings")).isEmpty(), "必须列出映射引用");
        assertTrue(String.valueOf(data.get("hint")).contains("force=true"));
        assertFalse(store.overlayExists(), "未确认前不得落盘");
    }

    @Test
    void 带force也无法删除被引用的关系并说明原因() {
        RelationEditRequest request = request(null);
        request.force = true;

        ApiException error = assertThrows(ApiException.class,
                () -> editor.delete("student_taught_by", request));

        assertEquals(409, error.httpStatus);
        assertEquals("DELETE_BLOCKED_BY_MAPPING", error.code);
        assertTrue(error.getMessage().contains("school-source.yaml"), "必须说清怎么解，实际：" + error.getMessage());
        assertFalse(store.overlayExists(), "会产生非法生效定义时绝不落盘");
    }

    @Test
    void 删除草稿里新增且未被引用的关系成功() {
        editor.add(request(validDefinition("teacher_mentor")));

        Map<String, Object> result = editor.delete("teacher_mentor", request(null));

        assertEquals(Boolean.TRUE, result.get("saved"));
        assertFalse(store.readOverlayText().contains("teacher_mentor"), "删除后草稿里不应再有该关系");
        assertEquals(10, countRelationsInDraft());
    }

    @Test
    void 恢复基线删除overlay并把草稿置为SUPERSEDED() {
        editor.add(request(validDefinition("teacher_mentor")));
        RelationEditRequest request = request(null);
        request.confirm = true;

        Map<String, Object> result = editor.reset(request);

        assertEquals(Boolean.TRUE, result.get("reset"));
        assertFalse(store.overlayExists(), "恢复基线必须删掉 overlay");
        assertEquals("BASELINE", result.get("definitionSource"));
        assertEquals(loader.baselineMappingVersion().mappingVersion,
                result.get("pendingMappingVersion"), "恢复基线也要登记待生效目标（基线版本）");
        verify(repository).markPendingMappingVersion("school-panorama",
                loader.baselineMappingVersion().mappingVersion,
                loader.baselineMappingVersion().definitionHash,
                RelationTypeDefinitionEditor.DEFAULT_OPERATOR, "恢复打包基线", "BASELINE");
        verify(repository).supersedeOtherPending("school-panorama",
                loader.baselineMappingVersion().mappingVersion);
    }

    @Test
    void 恢复基线需要二次确认() {
        ApiException error = assertThrows(ApiException.class, () -> editor.reset(request(null)));
        assertEquals(400, error.httpStatus);
        assertEquals("BAD_REQUEST", error.code);
    }

    @Test
    void 过期baseMappingVersion返回409() {
        RelationEditRequest request = request(validDefinition("teacher_mentor"));
        request.baseMappingVersion = "school-panorama:deadbeef0000";

        ApiException error = assertThrows(ApiException.class, () -> editor.add(request));

        assertEquals(409, error.httpStatus);
        assertEquals("VERSION_CONFLICT", error.code);
    }

    @Test
    void 有导入任务运行时拒绝编辑() {
        when(repository.hasRunningTask("FULL_IMPORT")).thenReturn(true);
        ApiException error = assertThrows(ApiException.class,
                () -> editor.add(request(validDefinition("teacher_mentor"))));
        assertEquals(409, error.httpStatus);
        assertEquals("TASK_CONFLICT", error.code);
    }

    @Test
    void 版本面板能给出草稿与生效版本对照() {
        editor.add(request(validDefinition("teacher_mentor")));

        Map<String, Object> versions = editor.versions();

        assertEquals("school-panorama", versions.get("sourceId"));
        assertEquals(baseVersion, versions.get("activeMappingVersion"));
        assertEquals(baseVersion, versions.get("baselineMappingVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) versions.get("draft");
        assertEquals(Boolean.TRUE, draft.get("exists"));
        assertEquals(Boolean.TRUE, draft.get("pendingApply"));
        assertNotNull(draft.get("draftMappingVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fallback = (Map<String, Object>) versions.get("lastFallback");
        assertEquals(Boolean.FALSE, fallback.get("occurred"));
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private RelationEditRequest request(RelationTypeDef def) {
        RelationEditRequest request = new RelationEditRequest();
        request.baseMappingVersion = baseVersion;
        request.relation = def;
        return request;
    }

    private int countRelationsInDraft() {
        // 草稿是完整 YAML，父类没有解析器：用 loader 的同一套规则数条数即可。
        String text = store.readOverlayText();
        int count = 0;
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("- id:")) {
                count++;
            }
        }
        return count;
    }

    private static RelationTypeDef validDefinition(String id) {
        RelationTypeDef def = new RelationTypeDef();
        def.id = id;
        def.displayName = "学生-导师（测试）";
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

    private static RelationTypeDef copyOf(RelationTypeDef source) {
        RelationTypeDef def = new RelationTypeDef();
        def.id = source.id;
        def.displayName = source.displayName;
        def.fromType = source.fromType;
        def.toType = source.toType;
        def.forwardName = source.forwardName;
        def.reverseName = source.reverseName;
        def.direction = source.direction;
        def.cardinality = source.cardinality;
        def.businessEffectiveTime = source.businessEffectiveTime;
        def.sourceMappingRef = source.sourceMappingRef;
        if (source.generation != null) {
            GenerationRule generation = new GenerationRule();
            generation.kind = source.generation.kind;
            generation.sourceTable = source.generation.sourceTable;
            generation.fkColumn = source.generation.fkColumn;
            generation.targetTable = source.generation.targetTable;
            generation.rule = source.generation.rule;
            generation.distinct = source.generation.distinct;
            generation.sourceColumn = source.generation.sourceColumn;
            generation.joinTable = source.generation.joinTable;
            generation.joinFilterColumn = source.generation.joinFilterColumn;
            generation.joinSelectColumn = source.generation.joinSelectColumn;
            generation.validFromColumns = source.generation.validFromColumns == null
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(source.generation.validFromColumns);
            def.generation = generation;
        }
        return def;
    }
}
