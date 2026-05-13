package com.stockrush.promotion.application;

public class DuplicateCouponException extends RuntimeException {

    public DuplicateCouponException(String couponCode) {
        super("Coupon code already exists: " + couponCode);
    }
}
