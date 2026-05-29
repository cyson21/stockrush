package com.stockrush.payment.infra.kafka;

import java.time.Instant;
import java.util.UUID;
/**
 * 카프카 메시지의 헤더/바디를 함께 보관하는 공통 이벤트 래퍼입니다.
 */


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

