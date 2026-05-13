package com.stockrush.promotion.application;

import java.math.BigDecimal;

public record CouponQuote(
    String couponCode,
    boolean applied,
    BigDecimal discountAmount,
    BigDecimal payAmount,
    String reason
) {
}
