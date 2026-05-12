package com.stockrush.order.infra.persistence;

import static com.stockrush.order.infra.persistence.JdbcTimestamps.timestampWithTimeZone;

import com.stockrush.order.application.OrderCommandRepository;
import com.stockrush.order.application.OrderLineSnapshot;
import com.stockrush.order.application.OrderSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderCommandRepository implements OrderCommandRepository {

    private final JdbcClient jdbcClient;

    JdbcOrderCommandRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void save(OrderSnapshot order, String idempotencyKey) {
        jdbcClient.sql("""
                insert into customer_orders (
                  order_id, member_id, status, saga_status, total_amount, idempotency_key, created_at, updated_at
                )
                values (:orderId, :memberId, :status, :sagaStatus, :totalAmount, :idempotencyKey, :createdAt, :updatedAt)
                """)
            .param("orderId", order.orderId())
            .param("memberId", order.memberId())
            .param("status", order.status().name())
            .param("sagaStatus", order.sagaStatus().name())
            .param("totalAmount", order.totalAmount())
            .param("idempotencyKey", idempotencyKey)
            .param("createdAt", timestampWithTimeZone(order.createdAt()))
            .param("updatedAt", timestampWithTimeZone(order.createdAt()))
            .update();

        for (OrderLineSnapshot item : order.items()) {
            saveItem(order, item);
        }
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
}
