// OrderDetailSnapshot: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.util.List;

public record OrderDetailSnapshot(
    String orderId,
    String memberId,
    OrderStatus status,
    SagaStatus sagaStatus,
    String paymentMethod,
    String couponCode,
    BigDecimal totalAmount,
    BigDecimal discountAmount,
    BigDecimal payableAmount,
    List<OrderDetailItemSnapshot> items
) {
    public OrderDetailSnapshot {
        items = List.copyOf(items);
    }
}
