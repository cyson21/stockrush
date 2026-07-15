package com.stockrush.promotion.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
/**
 * 도메인 규칙 조합과 저장소/이벤트 위임을 담당하는 핵심 유즈케이스 서비스입니다.
 */


@Service
public class PromotionCouponQueryService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");

    private final CouponQueryRepository couponQueryRepository;

    PromotionCouponQueryService(CouponQueryRepository couponQueryRepository) {
        this.couponQueryRepository = couponQueryRepository;
    }

    public List<CouponSnapshot> listByStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        return couponQueryRepository.listByStatus(status);
    }

    public CouponSnapshot getByCouponCode(String couponCode) {
        if (couponCode == null || couponCode.isBlank()) {
            throw new IllegalArgumentException("couponCode must not be blank");
        }
        return couponQueryRepository.findByCouponCode(couponCode)
            .orElseThrow(() -> new CouponNotFoundException(couponCode));
    }

    public CouponQuote quote(String couponCode, BigDecimal orderAmount) {
        if (couponCode == null || couponCode.isBlank()) {
            throw new IllegalArgumentException("couponCode must not be blank");
        }
        if (orderAmount == null || orderAmount.signum() < 0) {
            throw new IllegalArgumentException("orderAmount must be zero or positive");
        }

        CouponSnapshot coupon = couponQueryRepository.findByCouponCode(couponCode)
            .orElseThrow(() -> new CouponNotFoundException(couponCode));

        if (!"ACTIVE".equals(coupon.status())) {
            return notApplied(coupon.couponCode(), orderAmount, "COUPON_NOT_ACTIVE");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (now.isBefore(coupon.startsAt()) || now.isAfter(coupon.endsAt())) {
            return notApplied(coupon.couponCode(), orderAmount, "COUPON_OUT_OF_PERIOD");
        }
        if (orderAmount.compareTo(coupon.minOrderAmount()) < 0) {
            return notApplied(coupon.couponCode(), orderAmount, "MIN_ORDER_AMOUNT_NOT_MET");
        }

        BigDecimal discountAmount = calculateDiscount(coupon, orderAmount);
        BigDecimal payAmount = orderAmount.subtract(discountAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
        return new CouponQuote(coupon.couponCode(), true, discountAmount, payAmount, "APPLIED");
    }

    private CouponQuote notApplied(String couponCode, BigDecimal orderAmount, String reason) {
        return new CouponQuote(
            couponCode,
            false,
            BigDecimal.ZERO.setScale(2, RoundingMode.DOWN),
            orderAmount.setScale(2, RoundingMode.DOWN),
            reason
        );
    }

    private BigDecimal calculateDiscount(CouponSnapshot coupon, BigDecimal orderAmount) {
        BigDecimal discountAmount;
        if ("PERCENTAGE".equals(coupon.discountType())) {
            discountAmount = orderAmount.multiply(coupon.discountValue()).divide(ONE_HUNDRED, 2, RoundingMode.DOWN);
            if (coupon.maxDiscountAmount() != null && discountAmount.compareTo(coupon.maxDiscountAmount()) > 0) {
                discountAmount = coupon.maxDiscountAmount();
            }
        } else {
            discountAmount = coupon.discountValue();
        }
        return discountAmount.min(orderAmount).setScale(2, RoundingMode.DOWN);
    }
}
