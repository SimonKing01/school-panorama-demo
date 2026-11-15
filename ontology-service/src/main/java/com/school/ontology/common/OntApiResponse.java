package com.school.ontology.common;

/**
 * 本体 API 统一响应包装：{code, data, message}。
 *
 * <p>与原业务 API 的平铺结构（带 demoTime）严格分离，避免污染
 * {@code docs/API-CONTRACT.md}；成功固定 {@code code="OK"}。</p>
 *
 * @param <T> 负载类型
 */
public record OntApiResponse<T>(String code, T data, String message) {

    public static <T> OntApiResponse<T> ok(T data) {
        return new OntApiResponse<>("OK", data, "");
    }

    public static OntApiResponse<Object> ok() {
        return new OntApiResponse<>("OK", null, "");
    }

    public static <T> OntApiResponse<T> fail(String code, String message) {
        return new OntApiResponse<>(code, null, message);
    }

    public static <T> OntApiResponse<T> fail(String code, String message, T data) {
        return new OntApiResponse<>(code, data, message);
    }
}
