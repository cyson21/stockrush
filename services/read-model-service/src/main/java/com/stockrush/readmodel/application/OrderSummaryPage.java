// OrderSummaryPage: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.readmodel.application;

import java.util.List;

public record OrderSummaryPage(
    int page,
    int size,
    List<OrderSummaryProjection> items
) {
}
