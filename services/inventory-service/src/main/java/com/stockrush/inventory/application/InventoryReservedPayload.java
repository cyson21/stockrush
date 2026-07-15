// InventoryReservedPayload: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.inventory.application;

import java.time.Instant;
import java.util.List;

public record InventoryReservedPayload(
    String orderId,
    List<InventoryReservedItemPayload> items,
    Instant reservedAt
) {
    public InventoryReservedPayload {
        items = List.copyOf(items);
    }
}
