package com.stockrush.readmodel.application;

import java.util.List;

public record OrderSummaryPage(
    int page,
    int size,
    List<OrderSummaryProjection> items
) {
}
