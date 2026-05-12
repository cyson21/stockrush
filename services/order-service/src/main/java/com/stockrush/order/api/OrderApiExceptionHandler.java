package com.stockrush.order.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = CreateOrderController.class)
class OrderApiExceptionHandler {

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiResponse<Void>> handleMissingHeader(
        MissingRequestHeaderException exception,
        HttpServletRequest request
    ) {
        String code = "Idempotency-Key".equals(exception.getHeaderName())
            ? "COMMON_MISSING_IDEMPOTENCY_KEY"
            : "COMMON_MISSING_HEADER";

        return badRequest(code, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        return badRequest("ORDER_INVALID_REQUEST", "Order request is invalid.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        return badRequest("ORDER_INVALID_REQUEST", exception.getMessage(), request);
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String code, String message, HttpServletRequest request) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.badRequest()
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure(code, message, correlationId));
    }
}
