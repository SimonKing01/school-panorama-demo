package com.school.panorama.common;

/**
 * 统一业务异常。payload 用于调课失败时整段覆盖错误体（preview 结构），
 * 其余情况按 {code,message,conflicts,demoTime} 由全局处理器组装。
 */
public class ApiException extends RuntimeException {

    public final int httpStatus;
    public final String code;
    public final Object conflicts;
    public final Object payload;

    public ApiException(int httpStatus, String code, String message) {
        this(httpStatus, code, message, null, null);
    }

    public ApiException(int httpStatus, String code, String message, Object conflicts) {
        this(httpStatus, code, message, conflicts, null);
    }

    public ApiException(int httpStatus, String code, String message, Object conflicts, Object payload) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.conflicts = conflicts;
        this.payload = payload;
    }
}
