// ReadModelApiExceptionHandler: API 오류 응답과 상관관계 식별자를 일관되게 정리합니다.

package com.stockrush.readmodel.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = OrderReadModelController.class)
class ReadModelApiExceptionHandler {

    @ExceptionHandler(TrustedCustomerIdentityRequiredException.class)
    ResponseEntity<ApiResponse<Void>> handleTrustedCustomerIdentityRequired(
        TrustedCustomerIdentityRequiredException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("READ_MODEL_TRUSTED_IDENTITY_REQUIRED", exception.getMessage(), correlationId));
    }
}
