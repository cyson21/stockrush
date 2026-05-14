package com.stockrush.fulfillment.application;

import java.util.List;

public interface FulfillmentRequestQueryRepository {

    List<FulfillmentRequestSnapshot> list(
        String orderId,
        String status,
        int limit,
        int offset
    );
}
