package com.stockrush.promotion.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
/**
 * 도메인 상태 스냅샷을 보존해 멱등 처리와 감사 추적에 쓰이는 데이터 표현입니다.
 */


public record CouponUsageSnapshot(
    String orderId,
    String memberId,
    String couponCode,
    String status,
    BigDecimal orderAmount,
    BigDecimal discountAmount,
    BigDecimal payableAmount,
    OffsetDateTime reservedAt,
    OffsetDateTime consumedAt,
    OffsetDateTime releasedAt,
    String releaseReason,
    OffsetDateTime updatedAt
) {
}
