package com.stockrush.promotion.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
/**
 * 도메인 상태 스냅샷을 보존해 멱등 처리와 감사 추적에 쓰이는 데이터 표현입니다.
 */


public record CouponSnapshot(
    String couponCode,
    String name,
    String discountType,
    BigDecimal discountValue,
    BigDecimal minOrderAmount,
    BigDecimal maxDiscountAmount,
    String status,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt
) {
}
