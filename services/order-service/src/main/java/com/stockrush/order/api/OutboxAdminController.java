package com.stockrush.order.api;

import com.stockrush.order.infra.outbox.OutboxEventPage;
import com.stockrush.order.infra.outbox.OutboxEventQueryService;
import com.stockrush.order.infra.outbox.OutboxEventView;
import com.stockrush.order.infra.outbox.OutboxAdminAuditRepository;
import com.stockrush.order.infra.outbox.OutboxRequeueResult;
import com.stockrush.order.infra.outbox.OutboxRelayResult;
import com.stockrush.order.infra.outbox.OutboxRelayService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/outbox-events")
class OutboxAdminController {

    private static final String OPERATOR_HEADER_NAME = "X-Operator-Id";

    private final OutboxEventQueryService queryService;
    private final OutboxRelayService relayService;
    private final OutboxAdminAuditRepository auditRepository;

    OutboxAdminController(
        OutboxEventQueryService queryService,
        OutboxRelayService relayService,
        OutboxAdminAuditRepository auditRepository
    ) {
        this.queryService = queryService;
        this.relayService = relayService;
        this.auditRepository = auditRepository;
    }

    @GetMapping
    ResponseEntity<ApiResponse<OutboxEventPageResponse>> list(
        @RequestParam(defaultValue = "PENDING,FAILED") String status,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        OutboxEventPageResponse response = OutboxEventPageResponse.from(queryService.list(status, limit, offset));

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }

    @PostMapping("/retry")
    ResponseEntity<ApiResponse<OutboxRetryResponse>> retry(
        @RequestParam(defaultValue = "10") int batchSize,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }

        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        OutboxRelayResult result = relayService.publishPending(batchSize);
        auditRepository.record("RETRY_PENDING", batchSize, result.claimed(), operatorId, resolvedCorrelationId);
        OutboxRetryResponse response = OutboxRetryResponse.from(result);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }

    @PostMapping("/failed/requeue")
    ResponseEntity<ApiResponse<OutboxRequeueResponse>> requeueFailed(
        @RequestParam(defaultValue = "10") int batchSize,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }

        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        OutboxRequeueResult result = relayService.requeueFailed(batchSize);
        auditRepository.record("REQUEUE_FAILED", batchSize, result.updated(), operatorId, resolvedCorrelationId);
        OutboxRequeueResponse response = OutboxRequeueResponse.from(result);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }
}

record OutboxEventPageResponse(
    int limit,
    int offset,
    List<OutboxEventResponse> items
) {

    static OutboxEventPageResponse from(OutboxEventPage page) {
        return new OutboxEventPageResponse(
            page.limit(),
            page.offset(),
            page.items().stream().map(OutboxEventResponse::from).toList()
        );
    }
}

record OutboxEventResponse(
    UUID eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    String topic,
    String partitionKey,
    String payload,
    String status,
    int retryCount,
    int maxRetryCount,
    Instant nextRetryAt,
    String errorMessage,
    Instant createdAt,
    Instant publishedAt
) {

    static OutboxEventResponse from(OutboxEventView event) {
        return new OutboxEventResponse(
            event.eventId(),
            event.aggregateType(),
            event.aggregateId(),
            event.eventType(),
            event.topic(),
            event.partitionKey(),
            event.payload(),
            event.status().name(),
            event.retryCount(),
            event.maxRetryCount(),
            event.nextRetryAt(),
            event.errorMessage(),
            event.createdAt(),
            event.publishedAt()
        );
    }
}

record OutboxRetryResponse(
    int claimed,
    int published,
    int failed
) {

    static OutboxRetryResponse from(OutboxRelayResult result) {
        return new OutboxRetryResponse(result.claimed(), result.published(), result.failed());
    }
}

record OutboxRequeueResponse(int updated) {

    static OutboxRequeueResponse from(OutboxRequeueResult result) {
        return new OutboxRequeueResponse(result.updated());
    }
}
