package com.stockrush.inventory.infra.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxRelayEvent(
    long id,
    UUID eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    int eventVersion,
    String topic,
    String partitionKey,
    String correlationId,
    String idempotencyKey,
    String payload,
    String headersJson,
    int retryCount,
    int maxRetryCount,
    Instant occurredAt
) {
}
