package com.stockrush.payment.api;

import com.stockrush.payment.application.OutboxQueryService;
import com.stockrush.payment.application.OutboxQueryService.OutboxQueryResult;
import com.stockrush.payment.infra.outbox.OutboxAdminAuditRepository;
import com.stockrush.payment.infra.outbox.OutboxRequeueResult;
import com.stockrush.payment.infra.outbox.OutboxRelayResult;
import com.stockrush.payment.infra.outbox.OutboxRelayService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/admin/outbox-events")
class AdminOutboxEventsController {

    private static final String OPERATOR_HEADER_NAME = "X-Operator-Id";

    private final OutboxQueryService outboxQueryService;
    private final OutboxRelayService outboxRelayService;
    private final OutboxAdminAuditRepository auditRepository;

    AdminOutboxEventsController(
        OutboxQueryService outboxQueryService,
        OutboxRelayService outboxRelayService,
        OutboxAdminAuditRepository auditRepository
    ) {
        this.outboxQueryService = outboxQueryService;
        this.outboxRelayService = outboxRelayService;
        this.auditRepository = auditRepository;
    }

    @GetMapping
    ResponseEntity<ApiResponse<OutboxEventListResponse>> listOutboxEvents(
        @RequestParam(defaultValue = "PENDING,FAILED") String status,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        List<OutboxEventSummary> events = outboxQueryService.list(status, limit, offset).stream()
            .map(OutboxEventSummary::from)
            .toList();

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(new OutboxEventListResponse(limit, offset, events), resolvedCorrelationId));
    }

    @PostMapping("/retry")
    ResponseEntity<ApiResponse<OutboxRelayResult>> retryOutboxEvents(
        @RequestParam(defaultValue = "10") int batchSize,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }

        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        OutboxRelayResult result = outboxRelayService.publishPending(batchSize);
        auditRepository.record("RETRY_PENDING", batchSize, result.claimed(), operatorId, resolvedCorrelationId);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(result, resolvedCorrelationId));
    }

    @PostMapping("/failed/requeue")
    ResponseEntity<ApiResponse<OutboxRequeueResult>> requeueFailedOutboxEvents(
        @RequestParam(defaultValue = "10") int batchSize,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }

        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        OutboxRequeueResult result = outboxRelayService.requeueFailed(batchSize);
        auditRepository.record("REQUEUE_FAILED", batchSize, result.updated(), operatorId, resolvedCorrelationId);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(result, resolvedCorrelationId));
    }
}

record OutboxEventListResponse(
    int limit,
    int offset,
    List<OutboxEventSummary> items
) {
}

record OutboxEventSummary(
    String eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    int eventVersion,
    String topic,
    String partitionKey,
    String correlationId,
    String idempotencyKey,
    String payload,
    String status,
    int retryCount,
    int maxRetryCount,
    Instant createdAt,
    Instant nextRetryAt,
    Instant publishedAt,
    String errorMessage
) {

    static OutboxEventSummary from(OutboxQueryResult outboxEvent) {
        return new OutboxEventSummary(
            outboxEvent.eventId(),
            outboxEvent.aggregateType(),
            outboxEvent.aggregateId(),
            outboxEvent.eventType(),
            outboxEvent.eventVersion(),
            outboxEvent.topic(),
            outboxEvent.partitionKey(),
            outboxEvent.correlationId(),
            outboxEvent.idempotencyKey(),
            outboxEvent.payload(),
            outboxEvent.status(),
            outboxEvent.retryCount(),
            outboxEvent.maxRetryCount(),
            outboxEvent.createdAt(),
            outboxEvent.nextRetryAt(),
            outboxEvent.publishedAt(),
            outboxEvent.errorMessage()
        );
    }
}
