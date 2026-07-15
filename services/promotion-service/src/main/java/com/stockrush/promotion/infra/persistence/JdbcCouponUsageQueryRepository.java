package com.stockrush.promotion.infra.persistence;

import com.stockrush.promotion.application.CouponUsageQueryRepository;
import com.stockrush.promotion.application.CouponUsageSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
/**
 * JDBC 또는 JPA를 통해 영속 저장소를 직접 조회/갱신하며, 도메인 규칙 위임용 데이터 경계 역할을 합니다.
 */


@Repository
class JdbcCouponUsageQueryRepository implements CouponUsageQueryRepository {

    private final JdbcClient jdbcClient;

    JdbcCouponUsageQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<CouponUsageSnapshot> list(
        String couponCode,
        String memberId,
        String status,
        int limit,
        int offset
    ) {
        return jdbcClient.sql("""
                select order_id, member_id, coupon_code, status, order_amount, discount_amount,
                       payable_amount, reserved_at, consumed_at, released_at, release_reason, updated_at
                  from coupon_usages
                 where (:couponCodeFilter = false or coupon_code = :couponCode)
                   and (:memberIdFilter = false or member_id = :memberId)
                   and (:statusFilter = false or status = :status)
                 order by updated_at desc, id desc
                 limit :limit
                 offset :offset
                """)
            .param("couponCodeFilter", couponCode != null)
            .param("couponCode", couponCode == null ? "" : couponCode)
            .param("memberIdFilter", memberId != null)
            .param("memberId", memberId == null ? "" : memberId)
            .param("statusFilter", status != null)
            .param("status", status == null ? "" : status)
            .param("limit", limit)
            .param("offset", offset)
            .query(this::map)
            .list();
    }

    private CouponUsageSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new CouponUsageSnapshot(
            rs.getString("order_id"),
            rs.getString("member_id"),
            rs.getString("coupon_code"),
            rs.getString("status"),
            rs.getBigDecimal("order_amount"),
            rs.getBigDecimal("discount_amount"),
            rs.getBigDecimal("payable_amount"),
            rs.getObject("reserved_at", OffsetDateTime.class),
            rs.getObject("consumed_at", OffsetDateTime.class),
            rs.getObject("released_at", OffsetDateTime.class),
            rs.getString("release_reason"),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
