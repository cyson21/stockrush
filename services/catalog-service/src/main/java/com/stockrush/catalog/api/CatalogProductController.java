package com.stockrush.catalog.api;

import com.stockrush.catalog.application.CatalogProductQueryService;
import com.stockrush.catalog.application.ProductSnapshot;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
class CatalogProductController {

    private final CatalogProductQueryService catalogProductQueryService;

    CatalogProductController(CatalogProductQueryService catalogProductQueryService) {
        this.catalogProductQueryService = catalogProductQueryService;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<ProductResponse>>> listProducts(
        @RequestParam String status,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        List<ProductResponse> products = catalogProductQueryService.listByStatus(status).stream()
            .map(ProductResponse::from)
            .toList();

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(products, resolvedCorrelationId));
    }

    @GetMapping("/{productCode}")
    ResponseEntity<ApiResponse<ProductResponse>> getProduct(
        @PathVariable String productCode,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        ProductResponse product = ProductResponse.from(catalogProductQueryService.getByProductCode(productCode));

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(product, resolvedCorrelationId));
    }
}

record ProductResponse(
    String productCode,
    String name,
    String status,
    BigDecimal listPrice
) {

    static ProductResponse from(ProductSnapshot product) {
        return new ProductResponse(
            product.productCode(),
            product.name(),
            product.status(),
            product.listPrice()
        );
    }
}
