package com.stockrush.promotion.application;

import org.springframework.stereotype.Service;
/**
 * 도메인 규칙 조합과 저장소/이벤트 위임을 담당하는 핵심 유즈케이스 서비스입니다.
 */


@Service
public class PromotionCouponUsageQueryService {

    private final CouponUsageQueryRepository couponUsageQueryRepository;

    PromotionCouponUsageQueryService(CouponUsageQueryRepository couponUsageQueryRepository) {
        this.couponUsageQueryRepository = couponUsageQueryRepository;
    }

    public CouponUsagePage list(String couponCode, String memberId, String status, int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        return new CouponUsagePage(
            normalizedPage,
            normalizedSize,
            couponUsageQueryRepository.list(
                normalizeFilter(couponCode),
                normalizeFilter(memberId),
                normalizeFilter(status),
                normalizedSize,
                normalizedPage * normalizedSize
            )
        );
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int normalizePage(int page) {
        return Math.max(page, 0);
    }

    private int normalizeSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
