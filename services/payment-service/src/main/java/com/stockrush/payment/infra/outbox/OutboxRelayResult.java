package com.stockrush.payment.infra.outbox;

public record OutboxRelayResult(
    int claimed,
    int published,
    int failed
) {
}
