package com.stockrush.order.infra.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventView(
    UUID eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    String topic,
    String partitionKey,
    String payload,
    OutboxStatus status,
    int retryCount,
    int maxRetryCount,
    Instant nextRetryAt,
    String errorMessage,
    Instant createdAt,
    Instant publishedAt
) {
}
