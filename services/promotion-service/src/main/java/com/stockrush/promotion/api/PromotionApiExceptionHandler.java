package com.stockrush.promotion.api;

import com.stockrush.promotion.application.CouponNotFoundException;
import com.stockrush.promotion.application.DuplicateCouponException;
import com.stockrush.promotion.application.IdempotencyKeyConflictException;
import com.stockrush.promotion.application.PromotionDataIntegrityException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
/**
 * 예외 발생 시 비즈니스 예외를 일관된 API 응답 형식으로 변환해 반환하는 처리기입니다.
 */


@RestControllerAdvice(basePackages = "com.stockrush.promotion.api")
class PromotionApiExceptionHandler {

    @ExceptionHandler(CouponNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleCouponNotFound(
        CouponNotFoundException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("PROMOTION_COUPON_NOT_FOUND", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(DuplicateCouponException.class)
    ResponseEntity<ApiResponse<Void>> handleDuplicateCoupon(
        DuplicateCouponException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("PROMOTION_DUPLICATE_COUPON_CODE", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ApiResponse<Void>> handleIdempotencyKeyConflict(
        IdempotencyKeyConflictException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("PROMOTION_IDEMPOTENCY_KEY_CONFLICT", exception.getMessage(), correlationId));
    }

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
        return badRequest("PROMOTION_INVALID_REQUEST", "Promotion request is invalid.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        return badRequest("PROMOTION_INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(PromotionDataIntegrityException.class)
    ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
        PromotionDataIntegrityException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("PROMOTION_DATA_INTEGRITY_ERROR", exception.getMessage(), correlationId));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String code, String message, HttpServletRequest request) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.badRequest()
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure(code, message, correlationId));
    }
}
