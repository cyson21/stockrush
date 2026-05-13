package com.stockrush.order.application;

import java.math.BigDecimal;

public record CouponQuoteResult(
    String couponCode,
    boolean applied,
    BigDecimal discountAmount,
    BigDecimal payAmount,
    String reason
) {
}
