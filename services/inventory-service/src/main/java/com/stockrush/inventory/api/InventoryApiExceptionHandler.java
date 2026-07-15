// InventoryApiExceptionHandler: 이벤트/메시지 처리 흐름을 수신하고 도메인 상태 반영을 담당합니다.

package com.stockrush.inventory.api;

import com.stockrush.inventory.application.StockNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = InventoryStockController.class)
class InventoryApiExceptionHandler {

    @ExceptionHandler(StockNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleStockNotFound(
        StockNotFoundException exception,
        HttpServletRequest request
    ) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.status(404)
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure("INVENTORY_STOCK_NOT_FOUND", exception.getMessage(), correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        return badRequest("INVENTORY_INVALID_REQUEST", "Inventory request is invalid.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        return badRequest("INVENTORY_INVALID_REQUEST", exception.getMessage(), request);
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String code, String message, HttpServletRequest request) {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        return ResponseEntity.badRequest()
            .header(CorrelationIds.HEADER_NAME, correlationId)
            .body(ApiResponse.failure(code, message, correlationId));
    }
}
