package com.stockrush.order.application;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventRecord<T>(
    UUID eventId,
    String eventType,
    int eventVersion,
    String aggregateType,
    String aggregateId,
    String correlationId,
    UUID causationId,
    String idempotencyKey,
    Instant occurredAt,
    String sourceService,
    OutboxEventStatus status,
    String topic,
    String partitionKey,
    T payload
) {
}
