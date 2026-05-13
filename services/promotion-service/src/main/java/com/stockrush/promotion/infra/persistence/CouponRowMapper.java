package com.stockrush.promotion.infra.persistence;

import com.stockrush.promotion.application.CouponSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;

final class CouponRowMapper {

    private CouponRowMapper() {
    }

    static CouponSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new CouponSnapshot(
            rs.getString("coupon_code"),
            rs.getString("name"),
            rs.getString("discount_type"),
            rs.getBigDecimal("discount_value"),
            rs.getBigDecimal("min_order_amount"),
            rs.getBigDecimal("max_discount_amount"),
            rs.getString("status"),
            rs.getObject("starts_at", java.time.OffsetDateTime.class),
            rs.getObject("ends_at", java.time.OffsetDateTime.class)
        );
    }
}
