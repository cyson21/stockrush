package com.stockrush.order.application;

public interface OrderCommandRepository {

    void save(OrderSnapshot order, String idempotencyKey);
}

