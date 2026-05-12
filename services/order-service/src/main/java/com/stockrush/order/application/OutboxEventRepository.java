package com.stockrush.order.application;

public interface OutboxEventRepository {

    void save(OutboxEventRecord<?> event);
}
