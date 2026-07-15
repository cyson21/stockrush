// CouponQuoteClient: 외부 연동/모듈 간 호출에서 계약을 지키는 접점입니다.

package com.stockrush.order.application;

import java.math.BigDecimal;

public interface CouponQuoteClient {

    CouponQuoteResult quote(String couponCode, BigDecimal orderAmount, String correlationId);
}
