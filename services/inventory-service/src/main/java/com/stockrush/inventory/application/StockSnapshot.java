// StockSnapshot: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.inventory.application;

public record StockSnapshot(
    String skuId,
    String productCode,
    int availableQuantity,
    int reservedQuantity,
    long version
) {
}
