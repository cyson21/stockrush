// CouponQuoteUnavailableException: 도메인 예외를 명시적으로 구분해 오류 경로를 명확히 표현합니다.

package com.stockrush.order.application;

public class CouponQuoteUnavailableException extends RuntimeException {

    public CouponQuoteUnavailableException(String message) {
        super(message);
    }

    public CouponQuoteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
