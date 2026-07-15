package com.stockrush.promotion.api;

import java.util.Map;
/**
 * HTTP API 경계의 진입점으로, 요청 파라미터를 받아 서비스 호출 흐름을 연결합니다.
 */


record ApiResponse<T>(
    boolean success,
    T data,
    ApiError error,
    Trace trace
) {

    static <T> ApiResponse<T> success(T data, String correlationId) {
        return new ApiResponse<>(true, data, null, new Trace(correlationId));
    }

    static ApiResponse<Void> failure(String code, String message, String correlationId) {
        return new ApiResponse<>(false, null, new ApiError(code, message, Map.of()), new Trace(correlationId));
    }
}

record ApiError(String code, String message, Map<String, Object> details) {
}

record Trace(String correlationId) {
}
