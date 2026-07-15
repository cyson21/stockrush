package com.stockrush.fulfillment.application;

import java.util.List;
/**
 * 조회 흐름을 조립해 저장소 호출 결과를 응답 계층이 소비하기 쉬운 형태로 가공합니다.
 */


public interface FulfillmentRequestQueryRepository {

    List<FulfillmentRequestSnapshot> list(
        String orderId,
        String status,
        int limit,
        int offset
    );
}
