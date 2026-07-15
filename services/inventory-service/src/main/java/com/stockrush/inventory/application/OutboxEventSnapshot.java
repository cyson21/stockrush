// OutboxEventSnapshot: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.inventory.application;

import java.time.Instant;

public record OutboxEventSnapshot(
    long id,
    String eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    int eventVersion,
    String topic,
    String partitionKey,
    String correlationId,
    String idempotencyKey,
    String payload,
    String status,
    int retryCount,
    int maxRetryCount,
    Instant createdAt,
    Instant publishedAt,
    Instant nextRetryAt,
    String errorMessage
) {
}
