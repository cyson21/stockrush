// CatalogApiExceptionHandler: 이벤트/메시지 처리 흐름을 수신하고 도메인 상태 반영을 담당합니다.

package com.stockrush.catalog.api;

import com.stockrush.catalog.application.CatalogDataIntegrityException;
import com.stockrush.catalog.application.DuplicateProductException;
import com.stockrush.catalog.application.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.stockrush.catalog.api")
class CatalogApiExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleProductNotFound(
        ProductNotFoundException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("CATALOG_PRODUCT_NOT_FOUND", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(DuplicateProductException.class)
    ResponseEntity<ApiResponse<Void>> handleDuplicateProduct(
        DuplicateProductException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("CATALOG_DUPLICATE_PRODUCT_CODE", exception.getMessage(), correlationId));
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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse<Void>> handleMissingRequestParameter(
        MissingServletRequestParameterException exception,
        HttpServletRequest request
    ) {
        return badRequest("CATALOG_INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        return badRequest("CATALOG_INVALID_REQUEST", "Catalog request is invalid.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        return badRequest("CATALOG_INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(CatalogDataIntegrityException.class)
    ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
        CatalogDataIntegrityException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("CATALOG_DATA_INTEGRITY_ERROR", exception.getMessage(), correlationId));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String code, String message, HttpServletRequest request) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.badRequest()
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure(code, message, correlationId));
    }
}
