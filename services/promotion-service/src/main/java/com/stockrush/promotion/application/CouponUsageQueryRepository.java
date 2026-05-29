package com.stockrush.promotion.application;

import java.util.List;
/**
 * 조회 흐름을 조립해 저장소 호출 결과를 응답 계층이 소비하기 쉬운 형태로 가공합니다.
 */


public interface CouponUsageQueryRepository {

    List<CouponUsageSnapshot> list(
        String couponCode,
        String memberId,
        String status,
        int limit,
        int offset
    );
}
