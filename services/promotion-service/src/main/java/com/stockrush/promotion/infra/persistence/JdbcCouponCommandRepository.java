package com.stockrush.promotion.infra.persistence;

import com.stockrush.promotion.application.CouponCommandRepository;
import com.stockrush.promotion.application.CouponSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
/**
 * JDBC 또는 JPA를 통해 영속 저장소를 직접 조회/갱신하며, 도메인 규칙 위임용 데이터 경계 역할을 합니다.
 */


@Repository
class JdbcCouponCommandRepository implements CouponCommandRepository {

    private final JdbcClient jdbcClient;

    JdbcCouponCommandRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public CouponSnapshot create(CouponSnapshot coupon) {
        jdbcClient.sql("""
                insert into coupons (
                  coupon_code, name, discount_type, discount_value, min_order_amount,
                  max_discount_amount, status, starts_at, ends_at, created_at, updated_at
                )
                values (
                  :couponCode, :name, :discountType, :discountValue, :minOrderAmount,
                  :maxDiscountAmount, :status, :startsAt, :endsAt, now(), now()
                )
                """)
            .param("couponCode", coupon.couponCode())
            .param("name", coupon.name())
            .param("discountType", coupon.discountType())
            .param("discountValue", coupon.discountValue())
            .param("minOrderAmount", coupon.minOrderAmount())
            .param("maxDiscountAmount", coupon.maxDiscountAmount())
            .param("status", coupon.status())
            .param("startsAt", coupon.startsAt())
            .param("endsAt", coupon.endsAt())
            .update();

        return coupon;
    }
}
