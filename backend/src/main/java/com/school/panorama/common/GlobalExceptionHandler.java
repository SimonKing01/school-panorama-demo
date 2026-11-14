package com.school.panorama.common;

import com.school.panorama.service.DemoClockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 契约统一错误结构：{code,message,conflicts,demoTime}，HTTP 400/404/500。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final DemoClockService demoClock;

    public GlobalExceptionHandler(DemoClockService demoClock) {
        this.demoClock = demoClock;
    }

    @SuppressWarnings("unchecked")
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (e.payload instanceof Map) {
            body.putAll((Map<String, Object>) e.payload);
        } else {
            body.put("code", e.code);
            body.put("message", e.getMessage());
            body.put("conflicts", e.conflicts == null ? Resp.emptyConflicts() : e.conflicts);
        }
        body.putIfAbsent("demoTime", safeDemoTime());
        return ResponseEntity.status(e.httpStatus).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        Map<String, Object> body = Resp.map(
                "code", "INTERNAL_ERROR",
                "message", "服务异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                "conflicts", Resp.emptyConflicts(),
                "demoTime", safeDemoTime());
        return ResponseEntity.status(500).body(body);
    }

    private String safeDemoTime() {
        try {
            return demoClock.demoTimeString();
        } catch (Exception ex) {
            return Resp.ts(LocalDateTime.now());
        }
    }
}
