package com.stockrush.promotion.application;

import java.util.List;

public interface CouponUsageQueryRepository {

    List<CouponUsageSnapshot> list(
        String couponCode,
        String memberId,
        String status,
        int limit,
        int offset
    );
}
