package com.stockrush.payment.api;

import java.util.UUID;

final class CorrelationIds {

    static final String HEADER_NAME = "X-Correlation-Id";

    private CorrelationIds() {
    }

    static String resolve(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }
}
