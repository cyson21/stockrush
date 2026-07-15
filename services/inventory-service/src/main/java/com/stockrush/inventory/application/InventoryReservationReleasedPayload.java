// InventoryReservationReleasedPayload: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.inventory.application;

import java.time.Instant;
import java.util.List;

public record InventoryReservationReleasedPayload(
    String orderId,
    String reason,
    List<InventoryReservedItemPayload> items,
    Instant releasedAt
) {
    public InventoryReservationReleasedPayload {
        items = List.copyOf(items);
    }
}
