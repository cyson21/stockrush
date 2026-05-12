package com.stockrush.inventory.infra.outbox;

public record OutboxRelayResult(
    int claimed,
    int published,
    int failed
) {
}
