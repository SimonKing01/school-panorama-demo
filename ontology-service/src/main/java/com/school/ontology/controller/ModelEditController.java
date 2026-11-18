package com.school.ontology.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.ontology.common.OntApiResponse;
import com.school.ontology.core.model.DefinitionApplyService;
import com.school.ontology.core.model.ObjectEditRequest;
import com.school.ontology.core.model.ObjectTypeDefinitionEditor;
import com.school.ontology.core.model.RelationEditRequest;
import com.school.ontology.core.model.RelationTypeDefinitionEditor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 关系类型在线编辑的 6 个写/读端点（07 增量设计 §6）。
 *
 * <p>既有只读端点（{@code GET /model/relations}、{@code /types}、{@code /mappings}）
 * 结构完全不变，仍由 {@link ModelController} 提供；本类只做「新增能力」，
 * 不改任何既有响应结构（红线 ①）。</p>
 *
 * <p>所有错误走既有 {@code GlobalExceptionHandler}，因此 400/404/409 的
 * {@code {code, data, message}} 形状与其它本体接口一致；校验失败时
 * {@code data.errors[]} 为逐条中文错误，{@code data.relationId} 便于前端定位。</p>
 */
@RestController
@RequestMapping("/api/ontology/model")
public class ModelEditController {

    /** 请求体解析用：静态常量复用，ObjectMapper 线程安全。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RelationTypeDefinitionEditor editor;
    /** C 档新增：对象类型在线编辑（六个展示层字段，其余硬拒绝）。 */
    private final ObjectTypeDefinitionEditor objectEditor;
    private final DefinitionApplyService applyService;

    /** 既有构造保留，便于只装配关系编辑的调用点零改动。 */
    public ModelEditController(RelationTypeDefinitionEditor editor,
                              DefinitionApplyService applyService) {
        this(editor, null, applyService);
    }

    @Autowired
    public ModelEditController(RelationTypeDefinitionEditor editor,
                              ObjectTypeDefinitionEditor objectEditor,
                              DefinitionApplyService applyService) {
        this.editor = editor;
        this.objectEditor = objectEditor;
        this.applyService = applyService;
    }

    /** 新增一条关系类型（保存为草稿，需「应用到运行时」后生效）。 */
    @PostMapping("/relations")
    public OntApiResponse<Map<String, Object>> add(
            @RequestBody(required = false) Map<String, Object> body) {
        return OntApiResponse.ok(editor.add(parse(body)));
    }

    /** 修改一条关系类型；id 不允许改名（改 id 视为删 + 增）。 */
    @PutMapping("/relations/{id}")
    public OntApiResponse<Map<String, Object>> update(
            @PathVariable("id") String relationId,
            @RequestBody(required = false) Map<String, Object> body) {
        return OntApiResponse.ok(editor.update(relationId, parse(body)));
    }

    /** 删除一条关系类型；被映射引用时先返回 409 影响清单，需 force 二次确认。 */
    @DeleteMapping("/relations/{id}")
    public OntApiResponse<Map<String, Object>> delete(
            @PathVariable("id") String relationId,
            @RequestBody(required = false) Map<String, Object> body) {
        return OntApiResponse.ok(editor.delete(relationId, parse(body)));
    }

    /** 恢复打包基线：删除在线改动（overlay），需 confirm=true 二次确认。 */
    @PostMapping("/relations/reset")
    public OntApiResponse<Map<String, Object>> reset(
            @RequestBody(required = false) Map<String, Object> body) {
        return OntApiResponse.ok(editor.reset(parse(body)));
    }

    // ── 对象类型在线编辑（C 档）────────────────────────────────────────────

    /** 修改既有对象类型的展示层定义；保存为草稿，需「应用到运行时」后生效。 */
    @PutMapping("/object-types/{id}")
    public OntApiResponse<Map<String, Object>> updateObject(
            @PathVariable("id") String objectTypeId,
            @RequestBody(required = false) Map<String, Object> body) {
        return OntApiResponse.ok(objectEditor.update(objectTypeId, parseObject(body)));
    }

    /** 恢复对象类型打包基线：只删 object overlay，不改关系 overlay；需 confirm=true。 */
    @PostMapping("/object-types/reset")
    public OntApiResponse<Map<String, Object>> resetObject(
            @RequestBody(required = false) Map<String, Object> body) {
        return OntApiResponse.ok(objectEditor.reset(parseObject(body)));
    }

    /** 对象类型草稿状态：无草稿时 exists=false；objectTypes 始终给出可编辑的完整定义。 */
    @GetMapping("/object-types/draft")
    public OntApiResponse<Map<String, Object>> objectDraft() {
        return OntApiResponse.ok(objectEditor.draft());
    }

    /**
     * 应用到运行时：202（自重启）或 200（待手动重启 / 无改动）。
     *
     * <p>202 是与「已受理、进程即将重启」语义对应的状态码（07 增量设计 §6.6），
     * 前端据此进入轮询恢复；其余分支保持 200。</p>
     */
    @PostMapping("/relations/apply")
    public ResponseEntity<OntApiResponse<Map<String, Object>>> apply(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> data = applyService.apply(parse(body));
        HttpStatus status = Boolean.TRUE.equals(data.get("applying"))
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(OntApiResponse.ok(data));
    }

    /**
     * 版本与草稿状态：生效版本 / 草稿版本 / 上次回退原因 / 版本时间线。
     *
     * <p>关系侧已有实现负责总状态，这里补上 {@code draft.objectTypes}：
     * 对象与关系共用一条映射版本轴，但两份 overlay 各自可独立存在。</p>
     */
    @GetMapping("/versions")
    public OntApiResponse<Map<String, Object>> versions() {
        Map<String, Object> data = editor.versions();
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) data.get("draft");
        if (draft != null) {
            draft.put("objectTypes", objectEditor == null ? List.of() : objectEditor.draftViews());
            draft.put("objectTypesFromDraft", objectEditor != null && objectEditor.objectOverlayExists());
        }
        return OntApiResponse.ok(data);
    }

    /** 把 JSON 请求体转成强类型入参；空体返回空对象，由各闸门给出中文错误。 */
    private static RelationEditRequest parse(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return new RelationEditRequest();
        }
        return JSON.convertValue(body, RelationEditRequest.class);
    }

    /** 对象编辑请求体解析；未知字段忽略（与关系侧一致）。 */
    private static ObjectEditRequest parseObject(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return new ObjectEditRequest();
        }
        return JSON.convertValue(body, ObjectEditRequest.class);
    }
}
