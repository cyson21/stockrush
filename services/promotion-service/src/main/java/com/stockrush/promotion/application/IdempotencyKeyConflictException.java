package com.stockrush.promotion.application;
/**
 * 비즈니스 예외를 타입별로 분리해 상위 계층에서 정확한 분기 처리와 응답 매핑이 가능하게 합니다.
 */


public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency key was already used with a different coupon request: " + idempotencyKey);
    }
}
