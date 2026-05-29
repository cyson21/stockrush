// CorrelationIds: 공통 응답/상관관계 식별자 규격을 유지해 트랜잭션 추적을 일관화합니다.

package com.stockrush.inventory.api;

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
