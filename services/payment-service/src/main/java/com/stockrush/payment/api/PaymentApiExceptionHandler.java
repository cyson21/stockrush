package com.stockrush.payment.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
/**
 * 예외 발생 시 비즈니스 예외를 일관된 API 응답 형식으로 변환해 반환하는 처리기입니다.
 */


@RestControllerAdvice(basePackageClasses = AdminOutboxEventsController.class)
class PaymentApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.badRequest()
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("PAYMENT_INVALID_REQUEST", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(
        Exception exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("PAYMENT_INTERNAL_ERROR", exception.getMessage(), correlationId));
    }
}
