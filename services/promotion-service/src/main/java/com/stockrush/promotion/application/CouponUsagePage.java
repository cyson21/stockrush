package com.stockrush.promotion.application;

import java.util.List;

public record CouponUsagePage(
    int page,
    int size,
    List<CouponUsageSnapshot> items
) {

    public CouponUsagePage {
        items = List.copyOf(items);
    }
}
