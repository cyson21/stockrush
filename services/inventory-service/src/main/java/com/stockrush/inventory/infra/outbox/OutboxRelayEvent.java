// OutboxRelayEvent: 이벤트 인입·전송 경계에서 메시지 처리 순서를 보존합니다.

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
