package com.stockrush.promotion.infra.persistence;

import com.stockrush.promotion.application.CouponAdminCommandIdempotencyRepository;
import com.stockrush.promotion.application.IdempotencySnapshot;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
/**
 * JDBC 또는 JPA를 통해 영속 저장소를 직접 조회/갱신하며, 도메인 규칙 위임용 데이터 경계 역할을 합니다.
 */


@Repository
class JdbcCouponAdminCommandIdempotencyRepository implements CouponAdminCommandIdempotencyRepository {

    private final JdbcClient jdbcClient;

    JdbcCouponAdminCommandIdempotencyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<IdempotencySnapshot> findByKey(String idempotencyKey) {
        return jdbcClient.sql("""
                select idempotency_key, request_hash, coupon_code
                  from admin_coupon_command_idempotency
                 where idempotency_key = :idempotencyKey
                """)
            .param("idempotencyKey", idempotencyKey)
            .query((rs, rowNum) -> new IdempotencySnapshot(
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("coupon_code")
            ))
            .optional();
    }

    @Override
    public void record(String idempotencyKey, String requestHash, String couponCode) {
        jdbcClient.sql("""
                insert into admin_coupon_command_idempotency (idempotency_key, request_hash, coupon_code, created_at)
                values (:idempotencyKey, :requestHash, :couponCode, now())
                """)
            .param("idempotencyKey", idempotencyKey)
            .param("requestHash", requestHash)
            .param("couponCode", couponCode)
            .update();
    }
}
