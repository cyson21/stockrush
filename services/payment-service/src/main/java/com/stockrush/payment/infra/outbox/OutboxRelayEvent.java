package com.stockrush.payment.infra.outbox;

import java.time.Instant;
import java.util.UUID;
/**
 * 아웃박스 이벤트를 배치로 읽고 발행 상태를 갱신하여 멱등적으로 재시도되는 릴레이 플로우를 운영합니다.
 */


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
