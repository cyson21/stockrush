// OrderPageSnapshot: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.order.application;

import java.util.List;

public record OrderPageSnapshot(
    int page,
    int size,
    List<OrderSummarySnapshot> items
) {
    public OrderPageSnapshot {
        items = List.copyOf(items);
    }
}
