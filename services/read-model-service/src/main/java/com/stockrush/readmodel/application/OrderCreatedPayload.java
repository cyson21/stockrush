// OrderCreatedPayload: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.readmodel.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderCreatedPayload(
    String orderId,
    String memberId,
    List<OrderCreatedItemPayload> items,
    BigDecimal totalAmount,
    String couponCode,
    BigDecimal discountAmount,
    BigDecimal payableAmount,
    Instant createdAt
) {
    public OrderCreatedPayload {
        items = List.copyOf(items);
    }
}
