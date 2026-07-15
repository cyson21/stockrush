package com.stockrush.promotion.api;

import com.stockrush.promotion.application.CouponUsagePage;
import com.stockrush.promotion.application.CouponUsageSnapshot;
import com.stockrush.promotion.application.PromotionCouponUsageQueryService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/admin/coupon-usages")
class PromotionAdminCouponUsageController {

    private final PromotionCouponUsageQueryService promotionCouponUsageQueryService;

    PromotionAdminCouponUsageController(PromotionCouponUsageQueryService promotionCouponUsageQueryService) {
        this.promotionCouponUsageQueryService = promotionCouponUsageQueryService;
    }

    @GetMapping({"", "/"})
    ResponseEntity<ApiResponse<CouponUsagePageResponse>> listCouponUsages(
        @RequestParam(required = false) String couponCode,
        @RequestParam(required = false) String memberId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        CouponUsagePage usages = promotionCouponUsageQueryService.list(couponCode, memberId, status, page, size);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(CouponUsagePageResponse.from(usages), resolvedCorrelationId));
    }
}

record CouponUsagePageResponse(
    int page,
    int size,
    List<CouponUsageResponse> items
) {

    static CouponUsagePageResponse from(CouponUsagePage page) {
        return new CouponUsagePageResponse(
            page.page(),
            page.size(),
            page.items().stream().map(CouponUsageResponse::from).toList()
        );
    }
}

record CouponUsageResponse(
    String orderId,
    String memberId,
    String couponCode,
    String status,
    BigDecimal orderAmount,
    BigDecimal discountAmount,
    BigDecimal payableAmount,
    OffsetDateTime reservedAt,
    OffsetDateTime consumedAt,
    OffsetDateTime releasedAt,
    String releaseReason,
    OffsetDateTime updatedAt
) {

    static CouponUsageResponse from(CouponUsageSnapshot usage) {
        return new CouponUsageResponse(
            usage.orderId(),
            usage.memberId(),
            usage.couponCode(),
            usage.status(),
            usage.orderAmount(),
            usage.discountAmount(),
            usage.payableAmount(),
            usage.reservedAt(),
            usage.consumedAt(),
            usage.releasedAt(),
            usage.releaseReason(),
            usage.updatedAt()
        );
    }
}
