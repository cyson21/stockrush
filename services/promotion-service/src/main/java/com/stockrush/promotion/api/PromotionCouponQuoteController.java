package com.stockrush.promotion.api;

import com.stockrush.promotion.application.CouponQuote;
import com.stockrush.promotion.application.PromotionCouponQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/coupons")
class PromotionCouponQuoteController {

    private final PromotionCouponQueryService promotionCouponQueryService;

    PromotionCouponQuoteController(PromotionCouponQueryService promotionCouponQueryService) {
        this.promotionCouponQueryService = promotionCouponQueryService;
    }

    @PostMapping("/quote")
    ResponseEntity<ApiResponse<CouponQuoteResponse>> quoteCoupon(
        @Valid @RequestBody CouponQuoteRequest request,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        CouponQuoteResponse response = CouponQuoteResponse.from(
            promotionCouponQueryService.quote(request.couponCode(), request.orderAmount())
        );

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }
}

record CouponQuoteRequest(
    @NotBlank String couponCode,
    @NotNull @DecimalMin(value = "0.00") BigDecimal orderAmount
) {
}

record CouponQuoteResponse(
    String couponCode,
    boolean applied,
    BigDecimal discountAmount,
    BigDecimal payAmount,
    String reason
) {

    static CouponQuoteResponse from(CouponQuote quote) {
        return new CouponQuoteResponse(
            quote.couponCode(),
            quote.applied(),
            quote.discountAmount(),
            quote.payAmount(),
            quote.reason()
        );
    }
}
