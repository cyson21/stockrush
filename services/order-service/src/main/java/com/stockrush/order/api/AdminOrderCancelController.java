// AdminOrderCancelController: API 진입점으로 요청/응답 경계와 HTTP 흐름을 정리합니다.

package com.stockrush.order.api;

import com.stockrush.order.application.CancelDelayedOrderResult;
import com.stockrush.order.application.CancelDelayedOrderService;
import com.stockrush.order.infra.admin.AdminActionAuditRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderCancelController {

    private static final String OPERATOR_HEADER_NAME = "X-Operator-Id";

    private final CancelDelayedOrderService cancelDelayedOrderService;
    private final AdminActionAuditRepository auditRepository;

    AdminOrderCancelController(
        CancelDelayedOrderService cancelDelayedOrderService,
        AdminActionAuditRepository auditRepository
    ) {
        this.cancelDelayedOrderService = cancelDelayedOrderService;
        this.auditRepository = auditRepository;
    }

    @PostMapping("/{orderId}/cancel")
    ResponseEntity<ApiResponse<AdminOrderCancelResponse>> cancel(
        @PathVariable String orderId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        AdminOrderCancelResponse response = AdminOrderCancelResponse.from(
            cancelDelayedOrderService.cancel(orderId, idempotencyKey, resolvedCorrelationId)
        );
        auditRepository.record("DELAYED_ORDER_CANCEL_REQUESTED", orderId, operatorId, resolvedCorrelationId);

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
