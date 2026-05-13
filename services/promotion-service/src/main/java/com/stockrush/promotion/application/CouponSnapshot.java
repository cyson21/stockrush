package com.stockrush.promotion.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
