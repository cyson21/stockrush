package com.stockrush.inventory.infra.outbox;

public interface OutboxEventPublisher {

    void publish(OutboxRelayEvent event);
}
