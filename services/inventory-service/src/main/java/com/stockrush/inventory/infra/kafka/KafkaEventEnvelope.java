package com.stockrush.inventory.infra.kafka;

import java.time.Instant;
import java.util.UUID;

public record KafkaEventEnvelope<T>(
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
    T payload
) {
}

