package com.stockrush.order.infra.persistence;

import com.stockrush.order.application.OrderDataIntegrityException;
import com.stockrush.order.application.OrderDetailItemSnapshot;
import com.stockrush.order.application.OrderDetailSnapshot;
import com.stockrush.order.application.OrderQueryRepository;
import com.stockrush.order.application.OrderSagaSnapshot;
import com.stockrush.order.application.OrderSummarySnapshot;
import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderQueryRepository implements OrderQueryRepository {

    private final JdbcClient jdbcClient;

    JdbcOrderQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<OrderDetailSnapshot> findByOrderId(String orderId) {
        return jdbcClient.sql("""
                select order_id, member_id, status, saga_status, payment_method, total_amount
                from customer_orders
                where order_id = :orderId
                """)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new OrderHeaderRow(
                rs.getString("order_id"),
                rs.getString("member_id"),
                parseOrderStatus(orderId, rs.getString("status")),
                parseSagaStatus(orderId, rs.getString("saga_status")),
                rs.getString("payment_method"),
                rs.getBigDecimal("total_amount")
            ))
            .optional()
            .map(header -> header.toSnapshot(findItems(orderId)));
    }

    @Override
    public List<OrderSummarySnapshot> findRecentOrders(int page, int size, OrderStatus status, SagaStatus sagaStatus) {
        return jdbcClient.sql("""
                select co.order_id,
                       co.member_id,
                       co.status,
                       co.saga_status,
                       co.payment_method,
                       co.total_amount,
                       count(oi.id)::integer as item_count,
                       co.created_at,
                       co.updated_at
                from customer_orders co
                left join order_items oi on oi.order_id = co.order_id
                where (:statusFilter = false or co.status = :status)
                  and (:sagaStatusFilter = false or co.saga_status = :sagaStatus)
                group by co.id, co.order_id, co.member_id, co.status, co.saga_status,
                         co.payment_method, co.total_amount, co.created_at, co.updated_at
                order by co.created_at desc, co.id desc
                limit :limit
                offset :offset
                """)
            .param("statusFilter", status != null)
            .param("status", status == null ? "" : status.name())
            .param("sagaStatusFilter", sagaStatus != null)
            .param("sagaStatus", sagaStatus == null ? "" : sagaStatus.name())
            .param("limit", size)
            .param("offset", page * size)
            .query(this::mapOrderSummary)
            .list();
    }

    @Override
    public Optional<OrderSagaSnapshot> findSagaByOrderId(String orderId) {
        return jdbcClient.sql("""
                select co.order_id,
                       co.status,
                       co.saga_status,
                       co.updated_at as order_updated_at,
                       latest.event_type,
                       latest.payload ->> 'reason' as business_reason,
                       latest.error_message,
                       latest.retry_count,
                       latest.created_at as event_created_at
                from customer_orders co
                left join lateral (
                  select event_type, payload, error_message, retry_count, created_at
                  from outbox_events
                  where aggregate_id = co.order_id
                  order by created_at desc, id desc
                  limit 1
                ) latest on true
                where co.order_id = :orderId
                """)
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapOrderSaga(orderId, rs))
            .optional();
    }

    private List<OrderDetailItemSnapshot> findItems(String orderId) {
        return jdbcClient.sql("""
                select product_code, sku_id, quantity, unit_price
                from order_items
                where order_id = :orderId
                order by id
                """)
            .param("orderId", orderId)
            .query((rs, rowNum) -> {
                BigDecimal unitPrice = rs.getBigDecimal("unit_price");
                int quantity = rs.getInt("quantity");
                return new OrderDetailItemSnapshot(
                    rs.getString("product_code"),
                    rs.getString("sku_id"),
                    quantity,
                    unitPrice,
                    unitPrice.multiply(BigDecimal.valueOf(quantity))
                );
            })
            .list();
    }

    private OrderStatus parseOrderStatus(String orderId, String value) {
        try {
            return OrderStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new OrderDataIntegrityException("Invalid order status for orderId: " + orderId, exception);
        }
    }

    private SagaStatus parseSagaStatus(String orderId, String value) {
        try {
            return SagaStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new OrderDataIntegrityException("Invalid order saga status for orderId: " + orderId, exception);
        }
    }

    private OrderSummarySnapshot mapOrderSummary(ResultSet rs, int rowNum) throws SQLException {
        String orderId = rs.getString("order_id");
        return new OrderSummarySnapshot(
            orderId,
            rs.getString("member_id"),
            parseOrderStatus(orderId, rs.getString("status")),
            parseSagaStatus(orderId, rs.getString("saga_status")),
            rs.getString("payment_method"),
            rs.getBigDecimal("total_amount"),
            rs.getInt("item_count"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private OrderSagaSnapshot mapOrderSaga(String orderId, ResultSet rs) throws SQLException {
        OrderStatus orderStatus = parseOrderStatus(orderId, rs.getString("status"));
        SagaStatus sagaStatus = parseSagaStatus(orderId, rs.getString("saga_status"));
        OffsetDateTime eventCreatedAt = rs.getObject("event_created_at", OffsetDateTime.class);
        OffsetDateTime orderUpdatedAt = rs.getObject("order_updated_at", OffsetDateTime.class);

        return new OrderSagaSnapshot(
            rs.getString("order_id"),
            orderStatus,
            sagaStatus,
            failedAt(sagaStatus, eventCreatedAt, orderUpdatedAt),
            rs.getString("business_reason"),
            rs.getString("error_message"),
            rs.getString("event_type"),
            rs.getObject("retry_count") == null ? 0 : rs.getInt("retry_count")
        );
    }

    private Instant failedAt(SagaStatus sagaStatus, OffsetDateTime eventCreatedAt, OffsetDateTime orderUpdatedAt) {
        if (sagaStatus != SagaStatus.FAILED) {
            return null;
        }
        return eventCreatedAt == null ? orderUpdatedAt.toInstant() : eventCreatedAt.toInstant();
    }

    private record OrderHeaderRow(
        String orderId,
        String memberId,
        OrderStatus status,
        SagaStatus sagaStatus,
        String paymentMethod,
        BigDecimal totalAmount
    ) {

        OrderDetailSnapshot toSnapshot(List<OrderDetailItemSnapshot> items) {
            return new OrderDetailSnapshot(
                orderId,
                memberId,
                status,
                sagaStatus,
                paymentMethod,
                totalAmount,
                items
            );
        }
    }
}
