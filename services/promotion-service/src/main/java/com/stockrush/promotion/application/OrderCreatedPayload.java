package com.stockrush.promotion.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
/**
 * 이벤트·명령의 입력/출력 형식을 고정하는 페이로드 DTO로, 계약 안정성과 역직렬화 안정성을 돕습니다.
 */


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
