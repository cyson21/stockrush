// JdbcOrderCommandRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.order.infra.persistence;

import static com.stockrush.order.infra.persistence.JdbcTimestamps.timestampWithTimeZone;

import com.stockrush.order.application.OrderCommandRepository;
import com.stockrush.order.application.OrderLineSnapshot;
import com.stockrush.order.application.OrderSnapshot;
import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderCommandRepository implements OrderCommandRepository {

    private final JdbcClient jdbcClient;

    JdbcOrderCommandRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean saveIfAbsent(OrderSnapshot order, String idempotencyKey) {
        int inserted = jdbcClient.sql("""
                insert into customer_orders (
                  order_id, member_id, status, saga_status, total_amount, discount_amount, payable_amount, coupon_code, payment_method,
                  idempotency_key, created_at, updated_at
                )
                values (
                  :orderId, :memberId, :status, :sagaStatus, :totalAmount, :discountAmount, :payableAmount, :couponCode, :paymentMethod,
                  :idempotencyKey, :createdAt, :updatedAt
                )
                on conflict (idempotency_key) do nothing
                """)
            .param("orderId", order.orderId())
            .param("memberId", order.memberId())
            .param("status", order.status().name())
            .param("sagaStatus", order.sagaStatus().name())
            .param("totalAmount", order.totalAmount())
            .param("discountAmount", order.discountAmount())
            .param("payableAmount", order.payableAmount())
            .param("couponCode", order.couponCode())
            .param("paymentMethod", order.paymentMethod())
            .param("idempotencyKey", idempotencyKey)
            .param("createdAt", timestampWithTimeZone(order.createdAt()))
            .param("updatedAt", timestampWithTimeZone(order.createdAt()))
            .update();

        if (inserted == 0) {
            return false;
        }

        for (OrderLineSnapshot item : order.items()) {
            saveItem(order, item);
        }
        return true;
    }

    @Override
    public Optional<OrderSnapshot> findByIdempotencyKey(String idempotencyKey) {
        Optional<OrderRow> order = jdbcClient.sql("""
                select order_id, member_id, status, saga_status, payment_method, coupon_code,
                       total_amount, discount_amount, payable_amount, created_at
                from customer_orders
                where idempotency_key = :idempotencyKey
                """)
            .param("idempotencyKey", idempotencyKey)
            .query((rs, rowNum) -> new OrderRow(
                rs.getString("order_id"),
                rs.getString("member_id"),
                rs.getString("status"),
                rs.getString("saga_status"),
                rs.getString("payment_method"),
                rs.getString("coupon_code"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("payable_amount"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
            ))
            .optional();

        return order.map(row -> new OrderSnapshot(
            row.orderId(),
            row.memberId(),
            OrderStatus.valueOf(row.status()),
            SagaStatus.valueOf(row.sagaStatus()),
            row.paymentMethod(),
            row.couponCode(),
            row.totalAmount(),
            row.discountAmount(),
            row.payableAmount(),
            row.createdAt(),
            findItems(row.orderId())
        ));
    }

    private List<OrderLineSnapshot> findItems(String orderId) {
        return jdbcClient.sql("""
                select product_code, sku_id, quantity, unit_price, (quantity * unit_price) as line_amount
                from order_items
                where order_id = :orderId
                order by product_code, sku_id
                """)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new OrderLineSnapshot(
                rs.getString("product_code"),
                rs.getString("sku_id"),
                rs.getInt("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("line_amount")
            ))
            .list();
    }

    private void saveItem(OrderSnapshot order, OrderLineSnapshot item) {
        jdbcClient.sql("""
                insert into order_items (order_id, product_code, sku_id, quantity, unit_price, created_at)
                values (:orderId, :productCode, :skuId, :quantity, :unitPrice, :createdAt)
                """)
            .param("orderId", order.orderId())
            .param("productCode", item.productCode())
            .param("skuId", item.skuId())
            .param("quantity", item.quantity())
            .param("unitPrice", item.unitPrice())
            .param("createdAt", timestampWithTimeZone(order.createdAt()))
            .update();
    }

    private record OrderRow(
        String orderId,
        String memberId,
        String status,
        String sagaStatus,
        String paymentMethod,
        String couponCode,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        Instant createdAt
    ) {
    }
}
