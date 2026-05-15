package com.stockrush.order.application;

public class OrderForbiddenException extends RuntimeException {

    public OrderForbiddenException(String orderId) {
        super("Order access is forbidden: " + orderId);
    }
}
