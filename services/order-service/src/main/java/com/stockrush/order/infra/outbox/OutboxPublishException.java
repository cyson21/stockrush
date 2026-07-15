// OutboxPublishException: 도메인 예외를 명시적으로 구분해 오류 경로를 명확히 표현합니다.

package com.stockrush.order.infra.outbox;

public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(String message) {
        super(message);
    }
}
