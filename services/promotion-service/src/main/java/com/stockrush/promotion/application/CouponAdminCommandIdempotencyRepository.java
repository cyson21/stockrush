package com.stockrush.promotion.application;

import java.util.Optional;
/**
 * 도메인 쪽에서 사용할 영속 API를 추상화해 데이터 접근 책임을 분리한 인터페이스입니다.
 */


public interface CouponAdminCommandIdempotencyRepository {

    Optional<IdempotencySnapshot> findByKey(String idempotencyKey);

    void record(String idempotencyKey, String requestHash, String couponCode);
}
