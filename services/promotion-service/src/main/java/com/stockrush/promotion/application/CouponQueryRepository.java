package com.stockrush.promotion.application;

import java.util.List;
import java.util.Optional;

public interface CouponQueryRepository {

    List<CouponSnapshot> listByStatus(String status);

    Optional<CouponSnapshot> findByCouponCode(String couponCode);
}
