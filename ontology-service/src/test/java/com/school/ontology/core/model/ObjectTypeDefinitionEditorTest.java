package com.school.ontology.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对象类型在线编辑（C 档）：六字段可编辑、结构字段硬拒绝、草稿与生效分离、恢复基线只删对象 overlay。
 */
class ObjectTypeDefinitionEditorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    private ModelDefinitionLoader loader;
    private DefinitionFileStore objectStore;
    private DefinitionFileStore relationStore;
    private OntologyRepository repository;
    private ObjectTypeDefinitionEditor editor;
    private String baseVersion;

    @BeforeEach
    void setUp() {
        // 先建两份空 overlay 仓储、再建 loader：进程加载的是打包基线，草稿不会立即生效。
        relationStore = new DefinitionFileStore(directory, DefinitionFileStore.OVERLAY_FILE);
        objectStore = new DefinitionFileStore(directory, DefinitionFileStore.OBJECT_OVERLAY_FILE);
        loader = new ModelDefinitionLoader(relationStore, objectStore);
        repository = mock(OntologyRepository.class);
        baseVersion = loader.mappingVersion().mappingVersion;
        when(repository.activeMappingVersion(anyString()))
                .thenReturn(Map.<String, Object>of("mapping_version", baseVersion));
        when(repository.hasRunningTask(anyString())).thenReturn(false);
        when(repository.countObjects(anyString(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(20L);
        editor = new ObjectTypeDefinitionEditor(loader, objectStore, relationStore, repository);
    }

    @Test
    void 合法编辑保存为草稿但不改变运行期定义() {
        ObjectTypeDef student = student();
        student.displayName = "学生（测试）";

        Map<String, Object> result = editor.update("student", request(student));

        assertEquals(Boolean.TRUE, result.get("saved"));
        assertEquals(Boolean.FALSE, result.get("applied"));
        assertTrue(objectStore.overlayExists(), "草稿必须写进 object-types.yaml overlay");
        assertFalse(relationStore.overlayExists(), "对象编辑不能碰关系 overlay");
        assertTrue(objectStore.readOverlayText().contains("学生（测试）"));
        assertEquals("学生", loader.registry().typeOf("student").displayName,
                "保存不等于生效：运行期 registry 不能被草稿改动");
        assertNotEquals(baseVersion, result.get("pendingMappingVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> impact = (Map<String, Object>) result.get("impact");
        assertEquals(Boolean.TRUE, impact.get("restartRequired"));
        assertEquals(Boolean.FALSE, impact.get("fullRebuildRequired"));
    }

    @Test
    void 修改id被拒绝并说明globalId身份链原因() {
        ObjectTypeDef student = student();
        student.id = "student_v2";

        ApiException error = assertThrows(ApiException.class,
                () -> editor.update("student", request(student)));

        assertEquals(400, error.httpStatus);
        assertEquals("VALIDATION_FAILED", error.code);
        assertTrue(error.getMessage().contains("对象类型校验未通过"));
        assertTrue(errorsOf(error).stream().anyMatch(e -> e.contains("globalId") && e.contains("id")),
                "必须说明为什么，实际：" + errorsOf(error));
        assertFalse(objectStore.overlayExists(), "校验失败绝不落盘");
    }

    @Test
    void 修改属性displayName也被拒绝() {
        ObjectTypeDef student = student();
        student.attributes.get(0).displayName = "改名后的编号";

        ApiException error = assertThrows(ApiException.class,
                () -> editor.update("student", request(student)));

        assertTrue(errorsOf(error).stream().anyMatch(e -> e.contains("attributes")),
                "属性结构（含属性 displayName）属于硬边界，实际：" + errorsOf(error));
        assertFalse(objectStore.overlayExists());
    }

    @Test
    void listFields引用不存在字段时列出非法字段名() {
        ObjectTypeDef student = student();
        student.listFields.add("not_exist_field");

        ApiException error = assertThrows(ApiException.class,
                () -> editor.update("student", request(student)));

        assertTrue(errorsOf(error).stream().anyMatch(e -> e.contains("listFields")
                && e.contains("not_exist_field")), "必须逐条指出非法字段，实际：" + errorsOf(error));
        assertFalse(objectStore.overlayExists());
    }

    @Test
    void 新增非法可筛选字段被拒绝但存量来源列放行() {
        ObjectTypeDef student = student();
        student.displayName = "学生（测试）"; // 存量 schoolId/classId 必须不阻塞这次合法编辑
        Map<String, Object> ok = editor.update("student", request(student));
        assertEquals(Boolean.TRUE, ok.get("saved"), "基线里的 schoolId/classId 是存量来源列，应放行");

        ObjectTypeDef next = student();
        next.filterableFields.add("fake_filter_column");
        ApiException error = assertThrows(ApiException.class,
                () -> editor.update("student", request(next)));
        assertTrue(errorsOf(error).stream().anyMatch(e -> e.contains("filterableFields")
                && e.contains("fake_filter_column")), "新增非法筛选项必须逐条拒绝，实际：" + errorsOf(error));
    }

    @Test
    void 存量豁免只认编辑前定义保留存量并新增非法字段仍拒绝() {
        ObjectTypeDef candidate = student();
        // classId / schoolId 是编辑前定义里就有的来源列；badField 是本次新增的非法字段。
        assertTrue(candidate.filterableFields.contains("classId")
                && candidate.filterableFields.contains("schoolId"), "测试前提：学生类型含存量来源列");
        candidate.filterableFields.add("badField");

        ApiException error = assertThrows(ApiException.class,
                () -> editor.update("student", request(candidate)));

        List<String> errors = errorsOf(error);
        assertTrue(errors.stream().anyMatch(e -> e.contains("filterableFields") && e.contains("badField")),
                "新增非法字段必须逐条列出，实际：" + errors);
        assertTrue(errors.stream().noneMatch(e -> e.contains("classId") || e.contains("schoolId")),
                "存量字段不得出现在错误清单里（说明被正确豁免），实际：" + errors);
        assertFalse(objectStore.overlayExists(), "校验失败不落盘");
    }

    @Test
    void 删掉存量来源列再换别的非法字段仍被拒绝() {
        ObjectTypeDef candidate = student();
        candidate.filterableFields.remove("classId");
        candidate.filterableFields.add("anotherBadField");

        ApiException error = assertThrows(ApiException.class,
                () -> editor.update("student", request(candidate)));

        List<String> errors = errorsOf(error);
        assertTrue(errors.stream().anyMatch(e -> e.contains("anotherBadField")),
                "新加的非法字段必须被拒绝，实际：" + errors);
        assertTrue(errors.stream().noneMatch(e -> e.contains("classId")),
                "删掉的存量字段不能被当成新增非法字段，实际：" + errors);
        assertFalse(objectStore.overlayExists());
    }

    @Test
    void titleTemplate占位符非法时被拒绝() {
        ObjectTypeDef student = student();
        student.titleTemplate = "{notExistField}";

        ApiException error = assertThrows(ApiException.class,
                () -> editor.update("student", request(student)));

        assertTrue(errorsOf(error).stream().anyMatch(e -> e.contains("titleTemplate")
                && e.contains("notExistField")), "必须逐条指出非法占位符，实际：" + errorsOf(error));
        assertFalse(objectStore.overlayExists());
    }

    @Test
    void 修改titleTemplate时影响评估带对象数并标记需重建() {
        ObjectTypeDef student = student();
        student.titleTemplate = "{code} - {name}";

        Map<String, Object> result = editor.update("student", request(student));

        @SuppressWarnings("unchecked")
        Map<String, Object> impact = (Map<String, Object>) result.get("impact");
        assertEquals(Boolean.TRUE, impact.get("fullRebuildRequired"));
        assertEquals(20L, impact.get("objectCount"), "标题模板变化要报告该类型当前对象数");
        verify(repository).countObjects("student", List.of(), null, Map.of());
    }

    @Test
    void 同进程内可以继续编辑刚保存的对象草稿() {
        ObjectTypeDef first = student();
        first.displayName = "学生（第一次）";
        editor.update("student", request(first));
        String firstVersion = editor.draftVersion("school-panorama");

        ObjectTypeDef second = student();
        second.displayName = "学生（第二次）";
        second.listFields = List.of("name", "code");
        Map<String, Object> result = editor.update("student", request(second));

        assertTrue(objectStore.readOverlayText().contains("学生（第二次）"), "二次编辑必须读草稿而非 registry");
        assertTrue(objectStore.readOverlayText().contains("listFields"), "草稿必须是完整 YAML，不能只写改动的字段");
        assertNotEquals(firstVersion, result.get("pendingMappingVersion"));
        assertEquals(7, countObjectTypesInDraft(), "草稿仍是完整的 7 份对象定义");
    }

    @Test
    void 恢复基线只删除对象overlay并把目标登记为基线版本() {
        relationStore.writeOverlay(loader.effectiveRelationText());
        objectStore.writeOverlay(objectStoreText());
        ObjectEditRequest request = request(null);
        request.confirm = true;

        Map<String, Object> result = editor.reset(request);

        assertEquals(Boolean.TRUE, result.get("reset"));
        assertFalse(objectStore.overlayExists(), "对象 overlay 必须删除");
        assertTrue(relationStore.overlayExists(), "关系 overlay 必须原样保留");
        assertEquals("BASELINE", result.get("definitionSource"));
        verify(repository).markPendingMappingVersion(org.mockito.ArgumentMatchers.eq("school-panorama"),
                org.mockito.ArgumentMatchers.eq(String.valueOf(result.get("pendingMappingVersion"))), anyString(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq("BASELINE"));
    }

    @Test
    void 恢复基线需要二次确认() {
        ApiException error = assertThrows(ApiException.class, () -> editor.reset(request(null)));
        assertEquals(400, error.httpStatus);
        assertEquals("BAD_REQUEST", error.code);
        assertFalse(objectStore.overlayExists(), "未确认时不得改动 overlay");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private ObjectEditRequest request(ObjectTypeDef def) {
        ObjectEditRequest request = new ObjectEditRequest();
        request.baseMappingVersion = baseVersion;
        request.objectType = def;
        return request;
    }

    private ObjectTypeDef student() {
        // 用 JSON 往返做深拷贝，避免测试改动 registry 里的共享对象。
        return JSON.convertValue(loader.registry().typeOf("student"), ObjectTypeDef.class);
    }

    private String objectStoreText() {
        return "version: 1\nobjectTypes:\n  - id: sample\n    displayName: 临时草稿\n";
    }

    private int countObjectTypesInDraft() {
        String text = objectStore.readOverlayText();
        int count = 0;
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("- id:")) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorsOf(ApiException error) {
        return (List<String>) ((Map<String, Object>) error.data).get("errors");
    }
}
