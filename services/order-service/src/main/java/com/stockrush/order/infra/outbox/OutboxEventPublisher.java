package com.stockrush.order.infra.outbox;

public interface OutboxEventPublisher {

    void publish(OutboxRelayEvent event);
}
