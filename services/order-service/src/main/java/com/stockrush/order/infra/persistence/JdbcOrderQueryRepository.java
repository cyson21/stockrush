package com.stockrush.order.infra.persistence;

import com.stockrush.order.application.OrderDataIntegrityException;
import com.stockrush.order.application.OrderDetailItemSnapshot;
import com.stockrush.order.application.OrderDetailSnapshot;
import com.stockrush.order.application.OrderQueryRepository;
import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
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
