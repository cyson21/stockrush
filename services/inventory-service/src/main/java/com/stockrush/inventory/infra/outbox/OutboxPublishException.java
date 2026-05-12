package com.stockrush.inventory.infra.outbox;

public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(String message) {
        super(message);
    }
}
