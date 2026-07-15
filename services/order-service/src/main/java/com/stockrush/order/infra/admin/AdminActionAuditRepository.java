// AdminActionAuditRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.order.infra.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminActionAuditRepository {

    private static final String UNKNOWN = "unknown";
    private static final int MAX_TEXT_LENGTH = 100;

    private final JdbcClient jdbcClient;

    public AdminActionAuditRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void record(String action, String targetId, String operatorId, String correlationId) {
        jdbcClient.sql("""
                insert into admin_actions (
                  action, target_id, operator_id, correlation_id, created_at
                )
                values (:action, :targetId, :operatorId, :correlationId, now())
                """)
            .param("action", normalize(action))
            .param("targetId", normalize(targetId))
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
