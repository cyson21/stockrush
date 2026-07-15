package com.stockrush.promotion.api;

import com.stockrush.promotion.application.CouponCreateResult;
import com.stockrush.promotion.application.CouponSnapshot;
import com.stockrush.promotion.application.PromotionCouponAdminService;
import com.stockrush.promotion.application.PromotionCouponQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/admin/coupons")
class PromotionAdminCouponController {

    private final PromotionCouponAdminService promotionCouponAdminService;
    private final PromotionCouponQueryService promotionCouponQueryService;

    PromotionAdminCouponController(
        PromotionCouponAdminService promotionCouponAdminService,
        PromotionCouponQueryService promotionCouponQueryService
    ) {
        this.promotionCouponAdminService = promotionCouponAdminService;
        this.promotionCouponQueryService = promotionCouponQueryService;
    }

    @PostMapping
    ResponseEntity<ApiResponse<CouponResponse>> createCoupon(
        @Valid @RequestBody CreateCouponRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        CouponCreateResult result = promotionCouponAdminService.create(
            idempotencyKey,
            request.couponCode(),
            request.name(),
            request.discountType(),
            request.discountValue(),
            request.minOrderAmount(),
            request.maxDiscountAmount(),
            request.status(),
            request.startsAt(),
            request.endsAt()
        );
        CouponResponse response = CouponResponse.from(result.coupon());

        if (result.replayed()) {
            return ResponseEntity.ok()
                .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
                .body(ApiResponse.success(response, resolvedCorrelationId));
        }

        return ResponseEntity.created(URI.create("/api/admin/coupons/" + response.couponCode()))
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }

    @GetMapping("/{couponCode}")
    ResponseEntity<ApiResponse<CouponResponse>> getCoupon(
        @PathVariable String couponCode,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        CouponResponse response = CouponResponse.from(promotionCouponQueryService.getByCouponCode(couponCode));

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<CouponResponse>>> listCoupons(
        @RequestParam String status,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        List<CouponResponse> coupons = promotionCouponQueryService.listByStatus(status).stream()
            .map(CouponResponse::from)
            .toList();

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(coupons, resolvedCorrelationId));
    }
}

record CreateCouponRequest(
    @NotBlank String couponCode,
    @NotBlank String name,
    @NotBlank String discountType,
    @NotNull @DecimalMin(value = "0.01") BigDecimal discountValue,
    @NotNull @DecimalMin(value = "0.00") BigDecimal minOrderAmount,
    BigDecimal maxDiscountAmount,
    @NotBlank String status,
    @NotNull OffsetDateTime startsAt,
    @NotNull OffsetDateTime endsAt
) {
}

record CouponResponse(
    String couponCode,
    String name,
    String discountType,
    BigDecimal discountValue,
    BigDecimal minOrderAmount,
    BigDecimal maxDiscountAmount,
    String status,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt
) {

    static CouponResponse from(CouponSnapshot coupon) {
        return new CouponResponse(
            coupon.couponCode(),
            coupon.name(),
            coupon.discountType(),
            coupon.discountValue(),
            coupon.minOrderAmount(),
            coupon.maxDiscountAmount(),
            coupon.status(),
            coupon.startsAt(),
            coupon.endsAt()
        );
    }
}
