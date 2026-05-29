package com.stockrush.fulfillment.application;

import java.time.OffsetDateTime;
import java.util.UUID;
/**
 * 도메인 상태 스냅샷을 보존해 멱등 처리와 감사 추적에 쓰이는 데이터 표현입니다.
 */


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
