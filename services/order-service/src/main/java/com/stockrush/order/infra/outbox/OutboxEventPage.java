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
