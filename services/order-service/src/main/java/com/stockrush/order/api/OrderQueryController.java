package com.stockrush.order.api;

import com.stockrush.order.application.OrderDetailItemSnapshot;
import com.stockrush.order.application.OrderDetailSnapshot;
import com.stockrush.order.application.OrderQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderQueryController {

    private final OrderQueryService orderQueryService;

    OrderQueryController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    @GetMapping("/{orderId}")
    ResponseEntity<ApiResponse<OrderDetailResponse>> getDetail(
        @PathVariable String orderId,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        OrderDetailResponse response = OrderDetailResponse.from(orderQueryService.getDetail(orderId));

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }
}

record OrderDetailResponse(
    String orderId,
    String memberId,
    String status,
    String sagaStatus,
    String paymentMethod,
    String couponCode,
    BigDecimal totalAmount,
    BigDecimal discountAmount,
    BigDecimal payableAmount,
    List<OrderDetailItemResponse> items
) {

    static OrderDetailResponse from(OrderDetailSnapshot snapshot) {
        return new OrderDetailResponse(
            snapshot.orderId(),
            snapshot.memberId(),
            snapshot.status().name(),
            snapshot.sagaStatus().name(),
            snapshot.paymentMethod(),
            snapshot.couponCode(),
            snapshot.totalAmount(),
            snapshot.discountAmount(),
            snapshot.payableAmount(),
            snapshot.items().stream().map(OrderDetailItemResponse::from).toList()
        );
    }
}

record OrderDetailItemResponse(
    String productCode,
    String skuId,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineAmount
) {

    static OrderDetailItemResponse from(OrderDetailItemSnapshot item) {
        return new OrderDetailItemResponse(
            item.productCode(),
            item.skuId(),
            item.quantity(),
            item.unitPrice(),
            item.lineAmount()
        );
    }
}
