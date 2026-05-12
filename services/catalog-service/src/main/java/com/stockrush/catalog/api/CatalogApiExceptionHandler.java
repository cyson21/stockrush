package com.stockrush.catalog.api;

import com.stockrush.catalog.application.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = CatalogProductController.class)
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
}
