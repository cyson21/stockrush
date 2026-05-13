package com.stockrush.promotion.infra.persistence;

import com.stockrush.promotion.application.CouponQueryRepository;
import com.stockrush.promotion.application.CouponSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCouponQueryRepository implements CouponQueryRepository {

    private final JdbcClient jdbcClient;

    JdbcCouponQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<CouponSnapshot> listByStatus(String status) {
        return jdbcClient.sql("""
                select coupon_code, name, discount_type, discount_value, min_order_amount,
                       max_discount_amount, status, starts_at, ends_at
                  from coupons
                 where status = :status
                 order by created_at asc, id asc
                """)
            .param("status", status)
            .query(CouponRowMapper::map)
            .list();
    }

    @Override
    public Optional<CouponSnapshot> findByCouponCode(String couponCode) {
        return jdbcClient.sql("""
                select coupon_code, name, discount_type, discount_value, min_order_amount,
                       max_discount_amount, status, starts_at, ends_at
                  from coupons
                 where coupon_code = :couponCode
                """)
            .param("couponCode", couponCode)
            .query(CouponRowMapper::map)
            .optional();
    }
}
