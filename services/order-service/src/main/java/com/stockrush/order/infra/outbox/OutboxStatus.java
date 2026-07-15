// OutboxStatus: 식별자·상태 값의 의미를 고정해 도메인 규칙의 일관성을 지킵니다.

package com.stockrush.order.infra.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED
}

