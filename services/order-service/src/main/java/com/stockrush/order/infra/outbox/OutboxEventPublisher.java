// OutboxEventPublisher: 이벤트 인입·전송 경계에서 메시지 처리 순서를 보존합니다.

package com.stockrush.order.infra.outbox;

public interface OutboxEventPublisher {

    void publish(OutboxRelayEvent event);
}
