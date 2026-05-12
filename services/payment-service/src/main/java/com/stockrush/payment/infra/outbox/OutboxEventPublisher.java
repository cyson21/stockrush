package com.stockrush.payment.infra.outbox;

public interface OutboxEventPublisher {

    void publish(OutboxRelayEvent event);
}
