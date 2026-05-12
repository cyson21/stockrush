package com.stockrush.order.api;

import com.stockrush.order.application.OrderPageSnapshot;
import com.stockrush.order.application.OrderQueryService;
import com.stockrush.order.application.OrderSagaSnapshot;
import com.stockrush.order.application.OrderSummarySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderQueryController {

    private final OrderQueryService orderQueryService;

    AdminOrderQueryController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    @GetMapping
    ResponseEntity<ApiResponse<AdminOrderPageResponse>> listRecentOrders(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String sagaStatus,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        AdminOrderPageResponse response = AdminOrderPageResponse.from(
            orderQueryService.listRecentOrders(page, size, status, sagaStatus)
        );

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }

    @GetMapping("/{orderId}/saga")
    ResponseEntity<ApiResponse<AdminOrderSagaResponse>> getSaga(
        @PathVariable String orderId,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        AdminOrderSagaResponse response = AdminOrderSagaResponse.from(orderQueryService.getSaga(orderId));

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }
}

record AdminOrderPageResponse(
    int page,
    int size,
    List<AdminOrderSummaryResponse> items
) {

    static AdminOrderPageResponse from(OrderPageSnapshot snapshot) {
        return new AdminOrderPageResponse(
            snapshot.page(),
            snapshot.size(),
            snapshot.items().stream().map(AdminOrderSummaryResponse::from).toList()
        );
    }
}

record AdminOrderSummaryResponse(
    String orderId,
    String memberId,
    String status,
    String sagaStatus,
    String paymentMethod,
    BigDecimal totalAmount,
    int itemCount,
    Instant createdAt,
    Instant updatedAt
) {

    static AdminOrderSummaryResponse from(OrderSummarySnapshot snapshot) {
        return new AdminOrderSummaryResponse(
            snapshot.orderId(),
            snapshot.memberId(),
            snapshot.status().name(),
            snapshot.sagaStatus().name(),
            snapshot.paymentMethod(),
            snapshot.totalAmount(),
            snapshot.itemCount(),
            snapshot.createdAt(),
            snapshot.updatedAt()
        );
    }
}

record AdminOrderSagaResponse(
    String orderId,
    String orderStatus,
    String sagaStatus,
    Instant failedAt,
    String businessReason,
    String technicalErrorMessage,
    String lastEventType,
    int outboxAttempts
) {

    static AdminOrderSagaResponse from(OrderSagaSnapshot snapshot) {
        return new AdminOrderSagaResponse(
            snapshot.orderId(),
            snapshot.orderStatus().name(),
            snapshot.sagaStatus().name(),
            snapshot.failedAt(),
            snapshot.businessReason(),
            snapshot.technicalErrorMessage(),
            snapshot.lastEventType(),
            snapshot.outboxAttempts()
        );
    }
}
