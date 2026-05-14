package com.stockrush.fulfillment.application;

import java.util.List;

public record FulfillmentRequestPage(
    int page,
    int size,
    List<FulfillmentRequestSnapshot> items
) {

    public FulfillmentRequestPage {
        items = List.copyOf(items);
    }
}
