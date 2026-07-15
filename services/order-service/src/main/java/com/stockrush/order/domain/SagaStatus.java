// SagaStatus: 식별자·상태 값의 의미를 고정해 도메인 규칙의 일관성을 지킵니다.

package com.stockrush.order.domain;

public enum SagaStatus {
    STARTED,
    INVENTORY_REQUESTED,
    PAYMENT_REQUESTED,
    PAYMENT_DELAYED,
    PAYMENT_CANCEL_REQUESTED,
    COMPLETED,
    FAILED
}
