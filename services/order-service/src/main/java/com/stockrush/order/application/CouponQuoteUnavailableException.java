package com.stockrush.order.application;

public class CouponQuoteUnavailableException extends RuntimeException {

    public CouponQuoteUnavailableException(String message) {
        super(message);
    }

    public CouponQuoteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
