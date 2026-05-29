package com.stockrush.payment.infra.outbox;
/**
 * 아웃박스 이벤트 상태·오류 상태를 보관·관리해 실패 복구와 감사 이력을 유지합니다.
 */


public record OutboxRequeueResult(int updated) {
}
