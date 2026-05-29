// OutboxEventView: 이벤트 인입·전송 경계에서 메시지 처리 순서를 보존합니다.

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
