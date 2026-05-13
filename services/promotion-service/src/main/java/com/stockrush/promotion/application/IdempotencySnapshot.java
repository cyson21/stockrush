package com.stockrush.promotion.application;

public record IdempotencySnapshot(
    String idempotencyKey,
    String requestHash,
    String couponCode
) {
}
