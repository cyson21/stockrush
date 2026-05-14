package com.stockrush.fulfillment.application;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FulfillmentRequestSnapshot(
    UUID requestId,
    String orderId,
    String status,
    OffsetDateTime requestedAt,
    UUID sourceEventId,
    String correlationId,
    String idempotencyKey,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
