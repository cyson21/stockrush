package com.stockrush.payment.infra.outbox;
/**
 * 아웃박스 이벤트를 카프카 토픽으로 발행해 비동기 전달 책임을 수행하는 퍼블리셔입니다.
 */


public interface OutboxEventPublisher {

    void publish(OutboxRelayEvent event);
}
