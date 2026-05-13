package com.stockrush.order.infra.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxAdminAuditRepository {

    private static final String UNKNOWN = "unknown";
    private static final int MAX_TEXT_LENGTH = 100;

    private final JdbcClient jdbcClient;

    public OutboxAdminAuditRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void record(
        String action,
        int requestedBatchSize,
        int affectedCount,
        String operatorId,
        String correlationId
    ) {
        jdbcClient.sql("""
                insert into outbox_admin_actions (
                  action, requested_batch_size, affected_count, operator_id, correlation_id, created_at
                )
                values (:action, :requestedBatchSize, :affectedCount, :operatorId, :correlationId, now())
                """)
            .param("action", action)
            .param("requestedBatchSize", requestedBatchSize)
            .param("affectedCount", affectedCount)
            .param("operatorId", normalize(operatorId))
            .param("correlationId", normalize(correlationId))
            .update();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= MAX_TEXT_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_TEXT_LENGTH);
    }
}
