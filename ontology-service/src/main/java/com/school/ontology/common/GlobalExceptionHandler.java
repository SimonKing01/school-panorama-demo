package com.school.ontology.common;

import com.school.ontology.repository.OntologyRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 本体 API 错误结构：{code,data,message} + HTTP 400/404/409/500。
 * 服务异常额外记一条 ont_error_log，便于「同步失败」状态与失败重试入口定位。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final OntologyRepository repository;

    public GlobalExceptionHandler(OntologyRepository repository) {
        this.repository = repository;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<OntApiResponse<Object>> handleApi(ApiException e) {
        // data 原样带出（校验失败逐条 errors、删除影响清单等），既有异常 data 为 null 时结构不变。
        return ResponseEntity.status(e.httpStatus)
                .body(OntApiResponse.fail(e.code, e.getMessage(), e.data));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OntApiResponse<Object>> handleOther(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        try {
            repository.appendErrorLog("API", null, message);
        } catch (Exception ignored) {
            // 数据库不可用时不能让错误响应本身失败，错误日志属于尽力而为的可观测能力。
        }
        return ResponseEntity.status(500)
                .body(OntApiResponse.fail("INTERNAL_ERROR", "服务异常：" + message));
    }
}
