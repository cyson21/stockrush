package com.stockrush.fulfillment.application;

import org.springframework.stereotype.Service;

@Service
public class FulfillmentRequestQueryService {

    private final FulfillmentRequestQueryRepository fulfillmentRequestQueryRepository;

    FulfillmentRequestQueryService(FulfillmentRequestQueryRepository fulfillmentRequestQueryRepository) {
        this.fulfillmentRequestQueryRepository = fulfillmentRequestQueryRepository;
    }

    public FulfillmentRequestPage list(String orderId, String status, int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        return new FulfillmentRequestPage(
            normalizedPage,
            normalizedSize,
            fulfillmentRequestQueryRepository.list(
                normalizeFilter(orderId),
                normalizeFilter(status),
                normalizedSize,
                normalizedPage * normalizedSize
            )
        );
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int normalizePage(int page) {
        return Math.max(page, 0);
    }

    private int normalizeSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
