package com.stockrush.payment.application;

import com.stockrush.payment.infra.outbox.OutboxStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxQueryService {

    private final JdbcClient jdbcClient;

    public OutboxQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public List<OutboxQueryResult> list(String status, int limit, int offset) {
        validateLimitAndOffset(limit, offset);
        List<OutboxStatus> parsedStatuses = parseStatuses(status);

        StringBuilder sql = new StringBuilder("""
                select id,
                       event_id,
                       aggregate_type,
                       aggregate_id,
                       event_type,
                       event_version,
                       topic,
                       partition_key,
                       correlation_id,
                       idempotency_key,
                       payload::text as payload_json,
                       status,
                       retry_count,
                       max_retry_count,
                       created_at,
                       next_retry_at,
                       published_at,
                       error_message
                from outbox_events
                """);

        if (!parsedStatuses.isEmpty()) {
            sql.append("where status in (");
            for (int i = 0; i < parsedStatuses.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(":status").append(i);
            }
            sql.append(")\n");
        }

        sql.append("""
                order by created_at desc, id desc
                limit :limit
                offset :offset
                """);

        JdbcClient.StatementSpec query = jdbcClient.sql(sql.toString())
            .param("limit", limit)
            .param("offset", offset);

        for (int i = 0; i < parsedStatuses.size(); i++) {
            query.param("status" + i, parsedStatuses.get(i).name());
        }

        return query.query(this::toQueryResult)
            .list();
    }

    private List<OutboxStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }

        String[] rawStatuses = status.split(",");
        List<OutboxStatus> parsedStatuses = new ArrayList<>();

        for (String rawStatus : rawStatuses) {
            String trimmed = rawStatus.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            parsedStatuses.add(parseStatus(trimmed));
        }

        if (parsedStatuses.isEmpty()) {
            throw new IllegalArgumentException("status must include at least one non-empty value");
        }

        return parsedStatuses;
    }

    private OutboxStatus parseStatus(String value) {
        try {
            return OutboxStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid status: " + value, exception);
        }
    }

    private void validateLimitAndOffset(int limit, int offset) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
    }

    private OutboxQueryResult toQueryResult(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxQueryResult(
            rs.getString("event_id"),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getInt("event_version"),
            rs.getString("topic"),
            rs.getString("partition_key"),
            rs.getString("correlation_id"),
            rs.getString("idempotency_key"),
            rs.getString("payload_json"),
            rs.getString("status"),
            rs.getInt("retry_count"),
            rs.getInt("max_retry_count"),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)),
            toInstantOrNull(rs.getObject("next_retry_at", OffsetDateTime.class)),
            toInstantOrNull(rs.getObject("published_at", OffsetDateTime.class)),
            rs.getString("error_message")
        );
    }

    private Instant toInstant(OffsetDateTime timestamp) {
        return timestamp.toInstant();
    }

    private Instant toInstantOrNull(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record OutboxQueryResult(
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
    }
}
