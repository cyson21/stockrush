package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.util.List;
import java.util.Optional;

public interface OrderQueryRepository {

    Optional<OrderDetailSnapshot> findByOrderId(String orderId);

    List<OrderSummarySnapshot> findRecentOrders(int page, int size, OrderStatus status, SagaStatus sagaStatus);

    Optional<OrderSagaSnapshot> findSagaByOrderId(String orderId);
}
