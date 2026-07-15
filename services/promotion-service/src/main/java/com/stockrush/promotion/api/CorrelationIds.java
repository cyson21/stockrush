package com.stockrush.promotion.api;

import java.util.UUID;
/**
 * 상관관계 헤더 전달을 단일 DTO로 묶어 추적 키를 안정적으로 다루기 위한 보조 타입입니다.
 */


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
