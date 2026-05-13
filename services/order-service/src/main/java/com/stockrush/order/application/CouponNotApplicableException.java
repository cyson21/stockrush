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
