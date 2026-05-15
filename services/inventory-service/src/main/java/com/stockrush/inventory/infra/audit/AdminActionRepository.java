package com.stockrush.inventory.infra.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminActionRepository {

    private static final String UNKNOWN_OPERATOR = "unknown";
    private static final int MAX_TEXT_LENGTH = 100;

    private final JdbcClient jdbcClient;

    public AdminActionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void record(String action, String targetId, String operatorId, String correlationId) {
        jdbcClient.sql("""
                insert into admin_actions (
                  action, target_id, operator_id, correlation_id, created_at
                )
                values (:action, :targetId, :operatorId, :correlationId, now())
                """)
            .param("action", action)
            .param("targetId", truncate(targetId))
            .param("operatorId", normalize(operatorId))
            .param("correlationId", normalize(correlationId))
            .update();
    }

    private String normalize(String value) {
        return (value == null || value.isBlank()) ? UNKNOWN_OPERATOR : truncate(value.trim());
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= MAX_TEXT_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_TEXT_LENGTH);
    }
}
