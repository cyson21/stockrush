package com.stockrush.promotion.application;

public record CouponCreateResult(CouponSnapshot coupon, boolean replayed) {
}
