package com.stockrush.order.application;

import java.util.Optional;

public interface OrderQueryRepository {

    Optional<OrderDetailSnapshot> findByOrderId(String orderId);
}
