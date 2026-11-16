package com.school.ontology.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.ontology.common.OntApiResponse;
import com.school.ontology.core.model.DefinitionApplyService;
import com.school.ontology.core.model.RelationEditRequest;
import com.school.ontology.core.model.RelationTypeDefinitionEditor;
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
    private final DefinitionApplyService applyService;

    public ModelEditController(RelationTypeDefinitionEditor editor,
                              DefinitionApplyService applyService) {
        this.editor = editor;
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

    /** 版本与草稿状态：生效版本 / 草稿版本 / 上次回退原因 / 版本时间线。 */
    @GetMapping("/versions")
    public OntApiResponse<Map<String, Object>> versions() {
        return OntApiResponse.ok(editor.versions());
    }

    /** 把 JSON 请求体转成强类型入参；空体返回空对象，由各闸门给出中文错误。 */
    private static RelationEditRequest parse(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return new RelationEditRequest();
        }
        return JSON.convertValue(body, RelationEditRequest.class);
    }
}
