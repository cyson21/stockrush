// CatalogAdminProductController: API 진입점으로 요청/응답 경계와 HTTP 흐름을 정리합니다.

package com.stockrush.catalog.api;

import com.stockrush.catalog.application.CatalogProductAdminService;
import com.stockrush.catalog.infra.admin.AdminActionAuditRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/products")
class CatalogAdminProductController {

    private static final String OPERATOR_HEADER_NAME = "X-Operator-Id";

    private final CatalogProductAdminService catalogProductAdminService;
    private final AdminActionAuditRepository auditRepository;

    CatalogAdminProductController(
        CatalogProductAdminService catalogProductAdminService,
        AdminActionAuditRepository auditRepository
    ) {
        this.catalogProductAdminService = catalogProductAdminService;
        this.auditRepository = auditRepository;
    }

    @PostMapping
    ResponseEntity<ApiResponse<ProductResponse>> createProduct(
        @Valid @RequestBody CreateProductRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        ProductResponse response = ProductResponse.from(
            catalogProductAdminService.create(
                request.productCode(),
                request.name(),
                request.listPrice(),
                request.salesStatus()
            )
        );
        auditRepository.record("PRODUCT_CREATED", response.productCode(), operatorId, resolvedCorrelationId);

        return ResponseEntity.created(URI.create("/api/admin/products/" + response.productCode()))
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }

    @PutMapping("/{productCode}")
    ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
        @PathVariable String productCode,
        @Valid @RequestBody UpdateProductRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        ProductResponse response = ProductResponse.from(
            catalogProductAdminService.update(productCode, request.name(), request.listPrice(), request.salesStatus())
        );
        auditRepository.record("PRODUCT_UPDATED", response.productCode(), operatorId, resolvedCorrelationId);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }
}

record CreateProductRequest(
    @NotBlank String productCode,
    @NotBlank String name,
    @NotBlank String salesStatus,
    @NotNull @DecimalMin(value = "0.01") BigDecimal listPrice
) {
}

record UpdateProductRequest(
    @NotBlank String name,
    @NotBlank String salesStatus,
    @NotNull @DecimalMin(value = "0.01") BigDecimal listPrice
) {
}
