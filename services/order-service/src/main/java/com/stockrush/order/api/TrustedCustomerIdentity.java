package com.stockrush.order.api;

final class TrustedCustomerIdentity {

    private TrustedCustomerIdentity() {
    }

    static String require(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new TrustedCustomerIdentityRequiredException();
        }
        return subject.trim();
    }
}

class TrustedCustomerIdentityRequiredException extends RuntimeException {

    TrustedCustomerIdentityRequiredException() {
        super("Trusted customer identity is required.");
    }
}
