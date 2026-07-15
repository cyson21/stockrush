// OutboxAdminService: 비즈니스 핵심 흐름을 조합해 상태 변경과 유효성 규칙을 적용합니다.

package com.stockrush.inventory.application;

import com.stockrush.inventory.infra.outbox.OutboxRelayService;
import com.stockrush.inventory.infra.outbox.OutboxRequeueResult;
import com.stockrush.inventory.infra.outbox.OutboxRelayResult;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class OutboxAdminService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 200;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 500;
    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "PUBLISHING", "PUBLISHED", "FAILED");

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRelayService outboxRelayService;

    public OutboxAdminService(
        OutboxEventRepository outboxEventRepository,
        OutboxRelayService outboxRelayService
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxRelayService = outboxRelayService;
    }

    public List<OutboxEventSnapshot> listOutboxEvents(String status, int limit, int offset) {
        validatePagination(limit, offset);
        List<String> statuses = parseStatusFilter(status);
        return outboxEventRepository.findOutboxEvents(limit, offset, statuses);
    }

    public OutboxRelayResult retryOutboxEvents(int batchSize) {
        validateBatchSize(batchSize);
        return outboxRelayService.publishPending(batchSize);
    }

    public OutboxRequeueResult requeueFailedOutboxEvents(int batchSize) {
        validateBatchSize(batchSize);
        return outboxRelayService.requeueFailed(batchSize);
    }

    private void validatePagination(int limit, int offset) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
    }

    private void validateBatchSize(int batchSize) {
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between " + MIN_BATCH_SIZE + " and " + MAX_BATCH_SIZE);
        }
    }

    private List<String> parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String token : Arrays.stream(status.split(",")).toList()) {
            String normalized = token.trim().toUpperCase(Locale.ROOT);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("status must include non-empty values");
            }
            if (!VALID_STATUSES.contains(normalized)) {
                throw new IllegalArgumentException(
                    String.format("invalid status: %s (expected one of %s)", token, VALID_STATUSES)
                );
            }
            parsed.add(normalized);
        }
        return parsed.stream().toList();
    }
}
