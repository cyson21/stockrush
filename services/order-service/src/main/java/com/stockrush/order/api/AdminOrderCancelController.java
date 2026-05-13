package com.stockrush.order.api;

import com.stockrush.order.application.CancelDelayedOrderResult;
import com.stockrush.order.application.CancelDelayedOrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderCancelController {

    private final CancelDelayedOrderService cancelDelayedOrderService;

    AdminOrderCancelController(CancelDelayedOrderService cancelDelayedOrderService) {
        this.cancelDelayedOrderService = cancelDelayedOrderService;
    }

    @PostMapping("/{orderId}/cancel")
    ResponseEntity<ApiResponse<AdminOrderCancelResponse>> cancel(
        @PathVariable String orderId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        AdminOrderCancelResponse response = AdminOrderCancelResponse.from(
            cancelDelayedOrderService.cancel(orderId, idempotencyKey, resolvedCorrelationId)
        );

        return ResponseEntity.accepted()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }
}

record AdminOrderCancelResponse(
    String orderId,
    String status,
    String sagaStatus
) {

    static AdminOrderCancelResponse from(CancelDelayedOrderResult result) {
        return new AdminOrderCancelResponse(result.orderId(), result.status(), result.sagaStatus());
    }
}
