// OutboxEventPage: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.order.infra.outbox;

import java.util.List;

public record OutboxEventPage(
    int limit,
    int offset,
    List<OutboxEventView> items
) {
    public OutboxEventPage {
        items = List.copyOf(items);
    }
}
