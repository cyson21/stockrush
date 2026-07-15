// ProductSnapshot: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.catalog.application;

import java.math.BigDecimal;

public record ProductSnapshot(
    String productCode,
    String name,
    String status,
    BigDecimal listPrice
) {
}
