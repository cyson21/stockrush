// CreateOrderResult: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.order.application;

public record CreateOrderResult(
    OrderSnapshot order,
    OutboxEventRecord<OrderCreatedPayload> outboxEvent,
    boolean replayed
) {

    public CreateOrderResult(OrderSnapshot order, OutboxEventRecord<OrderCreatedPayload> outboxEvent) {
        this(order, outboxEvent, false);
    }

    public static CreateOrderResult replayed(OrderSnapshot order) {
        return new CreateOrderResult(order, null, true);
    }
}
