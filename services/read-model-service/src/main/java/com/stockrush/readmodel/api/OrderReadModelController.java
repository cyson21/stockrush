package com.stockrush.readmodel.api;

import com.stockrush.readmodel.application.OrderReadModelQueryService;
import com.stockrush.readmodel.application.OrderSummaryPage;
import com.stockrush.readmodel.application.OrderSummaryProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/read-model")
class OrderReadModelController {

    private final OrderReadModelQueryService queryService;

    OrderReadModelController(OrderReadModelQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/orders")
    ResponseEntity<ApiResponse<OrderSummaryPageResponse>> listCustomerOrders(
        @RequestParam String memberId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(
                OrderSummaryPageResponse.from(queryService.listCustomerOrders(memberId, page, size)),
                resolvedCorrelationId
            ));
    }

    @GetMapping("/admin/orders")
    ResponseEntity<ApiResponse<OrderSummaryPageResponse>> listAdminOrders(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(
                OrderSummaryPageResponse.from(queryService.listAdminOrders(status, page, size)),
                resolvedCorrelationId
            ));
    }
}

record OrderSummaryPageResponse(
    int page,
    int size,
    List<OrderSummaryResponse> items
) {

    static OrderSummaryPageResponse from(OrderSummaryPage page) {
        return new OrderSummaryPageResponse(
            page.page(),
            page.size(),
            page.items().stream().map(OrderSummaryResponse::from).toList()
        );
    }
}

record OrderSummaryResponse(
    String orderId,
    String memberId,
    String status,
    String sagaStatus,
    String couponCode,
    BigDecimal totalAmount,
    BigDecimal discountAmount,
    BigDecimal payableAmount,
    int itemCount,
    String cancellationReason,
    Instant createdAt,
    Instant updatedAt
) {

    static OrderSummaryResponse from(OrderSummaryProjection projection) {
        return new OrderSummaryResponse(
            projection.orderId(),
            projection.memberId(),
            projection.status(),
            projection.sagaStatus(),
            projection.couponCode(),
            projection.totalAmount(),
            projection.discountAmount(),
            projection.payableAmount(),
            projection.itemCount(),
            projection.cancellationReason(),
            projection.createdAt(),
            projection.updatedAt()
        );
    }
}
