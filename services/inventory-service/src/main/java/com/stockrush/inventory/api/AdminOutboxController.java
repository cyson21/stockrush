package com.stockrush.inventory.api;

import com.stockrush.inventory.application.OutboxAdminService;
import com.stockrush.inventory.application.OutboxEventSnapshot;
import com.stockrush.inventory.infra.outbox.OutboxAdminAuditRepository;
import com.stockrush.inventory.infra.outbox.OutboxRequeueResult;
import com.stockrush.inventory.infra.outbox.OutboxRelayResult;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/outbox-events")
class AdminOutboxController {

    private static final String OPERATOR_HEADER_NAME = "X-Operator-Id";

    private final OutboxAdminService outboxAdminService;
    private final OutboxAdminAuditRepository auditRepository;

    AdminOutboxController(
        OutboxAdminService outboxAdminService,
        OutboxAdminAuditRepository auditRepository
    ) {
        this.outboxAdminService = outboxAdminService;
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
        List<OutboxEventResponse> response = outboxAdminService.listOutboxEvents(status, limit, offset)
            .stream()
            .map(OutboxEventResponse::from)
            .toList();

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(new OutboxEventListResponse(limit, offset, response), resolvedCorrelationId));
    }

    @PostMapping("/retry")
    ResponseEntity<ApiResponse<OutboxRelayResult>> retryOutboxEvents(
        @RequestParam(defaultValue = "10") int batchSize,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        OutboxRelayResult response = outboxAdminService.retryOutboxEvents(batchSize);
        auditRepository.record("RETRY_PENDING", batchSize, response.claimed(), operatorId, resolvedCorrelationId);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }

    @PostMapping("/failed/requeue")
    ResponseEntity<ApiResponse<OutboxRequeueResult>> requeueFailedOutboxEvents(
        @RequestParam(defaultValue = "10") int batchSize,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @RequestHeader(value = OPERATOR_HEADER_NAME, required = false) String operatorId
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        OutboxRequeueResult response = outboxAdminService.requeueFailedOutboxEvents(batchSize);
        auditRepository.record("REQUEUE_FAILED", batchSize, response.updated(), operatorId, resolvedCorrelationId);

        return ResponseEntity.ok()
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }
}

record OutboxEventListResponse(
    int limit,
    int offset,
    List<OutboxEventResponse> items
) {
}

record OutboxEventResponse(
    long id,
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
    Instant publishedAt,
    Instant nextRetryAt,
    String errorMessage
) {

    static OutboxEventResponse from(OutboxEventSnapshot snapshot) {
        return new OutboxEventResponse(
            snapshot.id(),
            snapshot.eventId(),
            snapshot.aggregateType(),
            snapshot.aggregateId(),
            snapshot.eventType(),
            snapshot.eventVersion(),
            snapshot.topic(),
            snapshot.partitionKey(),
            snapshot.correlationId(),
            snapshot.idempotencyKey(),
            snapshot.payload(),
            snapshot.status(),
            snapshot.retryCount(),
            snapshot.maxRetryCount(),
            snapshot.createdAt(),
            snapshot.publishedAt(),
            snapshot.nextRetryAt(),
            snapshot.errorMessage()
        );
    }
}
