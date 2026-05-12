package com.stockrush.payment.infra.outbox;

public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(String message) {
        super(message);
    }
}
