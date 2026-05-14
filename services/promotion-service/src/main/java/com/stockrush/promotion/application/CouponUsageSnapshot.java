package com.stockrush.promotion.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
