package com.stockrush.order.application;

public class OrderIdempotencyReplayUnavailableException extends RuntimeException {

    public OrderIdempotencyReplayUnavailableException(String message) {
        super(message);
    }
}
