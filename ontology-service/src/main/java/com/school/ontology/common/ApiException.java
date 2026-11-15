package com.school.ontology.common;

/** 本体 API 统一业务异常，由全局处理器转成 {code,data,message} 结构。 */
public class ApiException extends RuntimeException {

    public final int httpStatus;
    public final String code;

    public ApiException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
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
}
