// CouponQuoteResult: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

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
