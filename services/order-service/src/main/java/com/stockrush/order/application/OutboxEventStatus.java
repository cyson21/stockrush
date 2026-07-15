// OutboxEventStatus: 이벤트 인입·전송 경계에서 메시지 처리 순서를 보존합니다.

package com.stockrush.order.application;

public enum OutboxEventStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED
}

