// OutboxEventRecord: 이벤트 인입·전송 경계에서 메시지 처리 순서를 보존합니다.

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
