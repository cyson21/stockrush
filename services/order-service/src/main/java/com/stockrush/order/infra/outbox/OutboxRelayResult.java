package com.stockrush.order.infra.outbox;

public record OutboxRelayResult(
    int claimed,
    int published,
    int failed
) {
}
