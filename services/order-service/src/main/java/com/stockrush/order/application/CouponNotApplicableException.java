// CouponNotApplicableException: 도메인 예외를 명시적으로 구분해 오류 경로를 명확히 표현합니다.

package com.stockrush.order.application;

public class CouponNotApplicableException extends RuntimeException {

    private final String reason;

    public CouponNotApplicableException(String reason) {
        super("Coupon could not be applied: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
