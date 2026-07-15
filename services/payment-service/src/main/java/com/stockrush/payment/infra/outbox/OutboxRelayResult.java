package com.stockrush.payment.infra.outbox;
/**
 * 아웃박스 이벤트를 배치로 읽고 발행 상태를 갱신하여 멱등적으로 재시도되는 릴레이 플로우를 운영합니다.
 */


public record OutboxRelayResult(
    int claimed,
    int published,
    int failed
) {
}
