package com.stockrush.order.infra.outbox;

public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(String message) {
        super(message);
    }
}
