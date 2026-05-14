package com.stockrush.order.application;

import java.util.Optional;

public interface OrderCommandRepository {

    boolean saveIfAbsent(OrderSnapshot order, String idempotencyKey);

    Optional<OrderSnapshot> findByIdempotencyKey(String idempotencyKey);
}
