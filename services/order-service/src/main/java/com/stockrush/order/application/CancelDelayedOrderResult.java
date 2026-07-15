// CancelDelayedOrderResult: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.order.application;

public record CancelDelayedOrderResult(
    String orderId,
    String status,
    String sagaStatus
) {
}
