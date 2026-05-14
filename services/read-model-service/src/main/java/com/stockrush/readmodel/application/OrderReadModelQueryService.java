package com.stockrush.readmodel.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OrderReadModelQueryService {

    private final JdbcClient jdbcClient;

    public OrderReadModelQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public OrderSummaryPage listCustomerOrders(String memberId, int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        return new OrderSummaryPage(
            normalizedPage,
            normalizedSize,
            jdbcClient.sql("""
                    select order_id, member_id, status, saga_status, coupon_code, total_amount,
                           discount_amount, payable_amount, item_count, cancellation_reason,
                           created_at, updated_at
                    from order_summaries
                    where member_id = :memberId
                    order by created_at desc, id desc
                    limit :limit
                    offset :offset
                """)
                .param("memberId", memberId)
                .param("limit", normalizedSize)
                .param("offset", normalizedPage * normalizedSize)
                .query(this::mapSummary)
                .list()
        );
    }

    public OrderSummaryPage listAdminOrders(String status, int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        return new OrderSummaryPage(
            normalizedPage,
            normalizedSize,
            jdbcClient.sql("""
                    select order_id, member_id, status, saga_status, coupon_code, total_amount,
                           discount_amount, payable_amount, item_count, cancellation_reason,
                           created_at, updated_at
                    from order_summaries
                    where (:statusFilter = false or status = :status)
                    order by created_at desc, id desc
                    limit :limit
                    offset :offset
                """)
                .param("statusFilter", status != null && !status.isBlank())
                .param("status", status == null ? "" : status)
                .param("limit", normalizedSize)
                .param("offset", normalizedPage * normalizedSize)
                .query(this::mapSummary)
                .list()
        );
    }

    private int normalizePage(int page) {
        return Math.max(page, 0);
    }

    private int normalizeSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private OrderSummaryProjection mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new OrderSummaryProjection(
            rs.getString("order_id"),
            rs.getString("member_id"),
            rs.getString("status"),
            rs.getString("saga_status"),
            rs.getString("coupon_code"),
            rs.getBigDecimal("total_amount"),
            rs.getBigDecimal("discount_amount"),
            rs.getBigDecimal("payable_amount"),
            rs.getInt("item_count"),
            rs.getString("cancellation_reason"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }
}
