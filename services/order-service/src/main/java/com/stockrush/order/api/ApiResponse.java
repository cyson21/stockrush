// ApiResponse: 공통 응답/상관관계 식별자 규격을 유지해 트랜잭션 추적을 일관화합니다.

package com.stockrush.order.api;

import java.util.Map;

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
