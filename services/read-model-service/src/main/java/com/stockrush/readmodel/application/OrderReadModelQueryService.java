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

    public OrderSummaryPage listCustomerOrders(
        String memberId,
        String orderId,
        String status,
        String sagaStatus,
        String couponCode,
        int page,
        int size
    ) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        String normalizedMemberId = normalizeFilter(memberId);
        String normalizedOrderId = normalizeFilter(orderId);
        String normalizedStatus = normalizeFilter(status);
        String normalizedSagaStatus = normalizeFilter(sagaStatus);
        String normalizedCouponCode = normalizeFilter(couponCode);
        return new OrderSummaryPage(
            normalizedPage,
            normalizedSize,
            jdbcClient.sql("""
                    select order_id, member_id, status, saga_status, coupon_code, total_amount,
                           discount_amount, payable_amount, item_count, cancellation_reason,
                           created_at, updated_at
                    from order_summaries
                    where member_id = :memberId
                      and (:orderIdFilter = false or order_id = :orderId)
                      and (:statusFilter = false or status = :status)
                      and (:sagaStatusFilter = false or saga_status = :sagaStatus)
                      and (:couponCodeFilter = false or coupon_code = :couponCode)
                    order by created_at desc, id desc
                    limit :limit
                    offset :offset
                """)
                .param("memberId", valueOrEmpty(normalizedMemberId))
                .param("orderIdFilter", hasFilter(normalizedOrderId))
                .param("orderId", valueOrEmpty(normalizedOrderId))
                .param("statusFilter", hasFilter(normalizedStatus))
                .param("status", valueOrEmpty(normalizedStatus))
                .param("sagaStatusFilter", hasFilter(normalizedSagaStatus))
                .param("sagaStatus", valueOrEmpty(normalizedSagaStatus))
                .param("couponCodeFilter", hasFilter(normalizedCouponCode))
                .param("couponCode", valueOrEmpty(normalizedCouponCode))
                .param("limit", normalizedSize)
                .param("offset", normalizedPage * normalizedSize)
                .query(this::mapSummary)
                .list()
        );
    }

    public OrderSummaryPage listAdminOrders(
        String orderId,
        String memberId,
        String status,
        String sagaStatus,
        String couponCode,
        int page,
        int size
    ) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        String normalizedOrderId = normalizeFilter(orderId);
        String normalizedMemberId = normalizeFilter(memberId);
        String normalizedStatus = normalizeFilter(status);
        String normalizedSagaStatus = normalizeFilter(sagaStatus);
        String normalizedCouponCode = normalizeFilter(couponCode);
        return new OrderSummaryPage(
            normalizedPage,
            normalizedSize,
            jdbcClient.sql("""
                    select order_id, member_id, status, saga_status, coupon_code, total_amount,
                           discount_amount, payable_amount, item_count, cancellation_reason,
                           created_at, updated_at
                    from order_summaries
                    where (:orderIdFilter = false or order_id = :orderId)
                      and (:memberIdFilter = false or member_id = :memberId)
                      and (:statusFilter = false or status = :status)
                      and (:sagaStatusFilter = false or saga_status = :sagaStatus)
                      and (:couponCodeFilter = false or coupon_code = :couponCode)
                    order by created_at desc, id desc
                    limit :limit
                    offset :offset
                """)
                .param("orderIdFilter", hasFilter(normalizedOrderId))
                .param("orderId", valueOrEmpty(normalizedOrderId))
                .param("memberIdFilter", hasFilter(normalizedMemberId))
                .param("memberId", valueOrEmpty(normalizedMemberId))
                .param("statusFilter", hasFilter(normalizedStatus))
                .param("status", valueOrEmpty(normalizedStatus))
                .param("sagaStatusFilter", hasFilter(normalizedSagaStatus))
                .param("sagaStatus", valueOrEmpty(normalizedSagaStatus))
                .param("couponCodeFilter", hasFilter(normalizedCouponCode))
                .param("couponCode", valueOrEmpty(normalizedCouponCode))
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

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean hasFilter(String value) {
        return value != null;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
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
