package com.stockrush.promotion.application;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency key was already used with a different coupon request: " + idempotencyKey);
    }
}
