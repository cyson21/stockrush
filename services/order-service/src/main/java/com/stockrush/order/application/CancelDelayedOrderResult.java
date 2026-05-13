package com.stockrush.order.application;

public record CancelDelayedOrderResult(
    String orderId,
    String status,
    String sagaStatus
) {
}
