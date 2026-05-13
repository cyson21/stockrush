package com.stockrush.order.application;

import java.math.BigDecimal;

public interface CouponQuoteClient {

    CouponQuoteResult quote(String couponCode, BigDecimal orderAmount, String correlationId);
}
