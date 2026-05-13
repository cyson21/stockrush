package com.stockrush.promotion.application;

public class CouponNotFoundException extends RuntimeException {

    public CouponNotFoundException(String couponCode) {
        super("Coupon not found: " + couponCode);
    }
}
