package com.school.ontology.common;

import java.util.List;
import java.util.Map;

/**
 * 本体 API 统一业务异常，由全局处理器转成 {code,data,message} 结构。
 *
 * <p>{@link #data} 用于把机器可读的补充信息随错误一起返回（如校验失败的逐条
 * {@code errors[]}、删除影响清单），既有两参构造保持 data=null，行为与改造前一致。</p>
 */
public class ApiException extends RuntimeException {

    public final int httpStatus;
    public final String code;
    public final Object data;

    public ApiException(int httpStatus, String code, String message) {
        this(httpStatus, code, message, null);
    }

    public ApiException(int httpStatus, String code, String message, Object data) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.data = data;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "TASK_CONFLICT", message);
    }

    /** 校验失败：逐条中文错误随 data.errors 返回，便于前端原位展示。 */
    public static ApiException validationFailed(String message, String relationId,
                                                List<String> errors) {
        return new ApiException(400, "VALIDATION_FAILED", message,
                Map.of("relationId", relationId == null ? "" : relationId,
                        "errors", errors == null ? List.of() : List.copyOf(errors)));
    }
}
