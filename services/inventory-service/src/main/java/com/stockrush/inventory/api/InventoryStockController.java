package com.stockrush.inventory.api;

import com.stockrush.inventory.application.InventoryStockService;
import com.stockrush.inventory.application.StockSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
class InventoryStockController {

    private final InventoryStockService inventoryStockService;

    InventoryStockController(InventoryStockService inventoryStockService) {
        this.inventoryStockService = inventoryStockService;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<StockResponse>>> listStocks(
        @RequestParam(required = false) String productCode,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        List<StockResponse> stocks = inventoryStockService.list(productCode).stream()
            .map(StockResponse::from)
            .toList();

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(stocks, resolvedCorrelationId));
    }

    @GetMapping("/{skuId}")
    ResponseEntity<ApiResponse<StockResponse>> getStock(
        @PathVariable String skuId,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        StockResponse stock = StockResponse.from(inventoryStockService.get(skuId));

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(stock, resolvedCorrelationId));
    }

    @PutMapping("/{skuId}")
    ResponseEntity<ApiResponse<StockResponse>> setStock(
        @PathVariable String skuId,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @Valid @RequestBody SetStockRequest request
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        StockResponse stock = StockResponse.from(
            inventoryStockService.setAvailableQuantity(skuId, request.productCode(), request.availableQuantity())
        );

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(stock, resolvedCorrelationId));
    }
}

record SetStockRequest(
    @NotBlank String productCode,
    @NotNull @Min(0) Integer availableQuantity
) {
}

record StockResponse(
    String skuId,
    String productCode,
    int availableQuantity,
    int reservedQuantity,
    long version
) {

    static StockResponse from(StockSnapshot stock) {
        return new StockResponse(
            stock.skuId(),
            stock.productCode(),
            stock.availableQuantity(),
            stock.reservedQuantity(),
            stock.version()
        );
    }
}
