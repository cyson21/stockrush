// OrderApiExceptionHandler: 이벤트/메시지 처리 흐름을 수신하고 도메인 상태 반영을 담당합니다.

package com.stockrush.order.api;

import com.stockrush.order.application.CouponNotApplicableException;
import com.stockrush.order.application.CouponQuoteUnavailableException;
import com.stockrush.order.application.OrderDataIntegrityException;
import com.stockrush.order.application.OrderForbiddenException;
import com.stockrush.order.application.OrderIdempotencyReplayUnavailableException;
import com.stockrush.order.application.OrderNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
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

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleOrderNotFound(
        OrderNotFoundException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("ORDER_NOT_FOUND", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(OrderForbiddenException.class)
    ResponseEntity<ApiResponse<Void>> handleOrderForbidden(
        OrderForbiddenException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("ORDER_FORBIDDEN", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(OrderDataIntegrityException.class)
    ResponseEntity<ApiResponse<Void>> handleOrderDataIntegrity(
        OrderDataIntegrityException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("ORDER_DATA_INTEGRITY_ERROR", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(OrderIdempotencyReplayUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> handleOrderIdempotencyReplayUnavailable(
        OrderIdempotencyReplayUnavailableException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .header("Retry-After", "1")
            .body(ApiResponse.failure("ORDER_IDEMPOTENCY_REPLAY_PENDING", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(CouponNotApplicableException.class)
    ResponseEntity<ApiResponse<Void>> handleCouponNotApplicable(
        CouponNotApplicableException exception,
        HttpServletRequest request
    ) {
        return badRequest("ORDER_COUPON_NOT_APPLICABLE", exception.getMessage(), request);
    }

    @ExceptionHandler(CouponQuoteUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> handleCouponQuoteUnavailable(
        CouponQuoteUnavailableException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("ORDER_COUPON_QUOTE_UNAVAILABLE", exception.getMessage(), correlationId));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String code, String message, HttpServletRequest request) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.badRequest()
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure(code, message, correlationId));
    }
}
