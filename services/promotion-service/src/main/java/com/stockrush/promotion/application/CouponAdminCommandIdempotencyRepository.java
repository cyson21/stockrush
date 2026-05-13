package com.stockrush.promotion.application;

import java.util.Optional;

public interface CouponAdminCommandIdempotencyRepository {

    Optional<IdempotencySnapshot> findByKey(String idempotencyKey);

    void record(String idempotencyKey, String requestHash, String couponCode);
}
