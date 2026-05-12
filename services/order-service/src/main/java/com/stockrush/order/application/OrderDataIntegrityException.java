package com.stockrush.order.application;

public class OrderDataIntegrityException extends RuntimeException {

    public OrderDataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
