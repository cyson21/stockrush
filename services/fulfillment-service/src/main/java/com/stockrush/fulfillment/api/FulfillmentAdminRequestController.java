package com.stockrush.fulfillment.api;

import com.stockrush.fulfillment.application.FulfillmentRequestPage;
import com.stockrush.fulfillment.application.FulfillmentRequestQueryService;
import com.stockrush.fulfillment.application.FulfillmentRequestSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
@RequestMapping("/api/admin/fulfillment-requests")
class FulfillmentAdminRequestController {

    private final FulfillmentRequestQueryService fulfillmentRequestQueryService;

    FulfillmentAdminRequestController(FulfillmentRequestQueryService fulfillmentRequestQueryService) {
        this.fulfillmentRequestQueryService = fulfillmentRequestQueryService;
    }

    @GetMapping({"", "/"})
    ResponseEntity<ApiResponse<FulfillmentRequestPageResponse>> listRequests(
        @RequestParam(required = false) String orderId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        FulfillmentRequestPage requests = fulfillmentRequestQueryService.list(orderId, status, page, size);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(FulfillmentRequestPageResponse.from(requests), resolvedCorrelationId));
    }
}

record FulfillmentRequestPageResponse(
    int page,
    int size,
    List<FulfillmentRequestResponse> items
) {

    static FulfillmentRequestPageResponse from(FulfillmentRequestPage page) {
        return new FulfillmentRequestPageResponse(
            page.page(),
            page.size(),
            page.items().stream().map(FulfillmentRequestResponse::from).toList()
        );
    }
}

record FulfillmentRequestResponse(
    UUID requestId,
    String orderId,
    String status,
    OffsetDateTime requestedAt,
    UUID sourceEventId,
    String correlationId,
    String idempotencyKey,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    static FulfillmentRequestResponse from(FulfillmentRequestSnapshot request) {
        return new FulfillmentRequestResponse(
            request.requestId(),
            request.orderId(),
            request.status(),
            request.requestedAt(),
            request.sourceEventId(),
            request.correlationId(),
            request.idempotencyKey(),
            request.createdAt(),
            request.updatedAt()
        );
    }
}
