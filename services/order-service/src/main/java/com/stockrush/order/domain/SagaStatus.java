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
