package com.stockrush.order.infra.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventQueryService {

    private final JdbcClient jdbcClient;

    public OutboxEventQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public OutboxEventPage list(String status, int limit, int offset) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }

        List<OutboxStatus> statuses = parseStatuses(status);
        List<String> statusNames = statuses.isEmpty()
            ? List.of("__NO_STATUS_FILTER__")
            : statuses.stream().map(Enum::name).toList();

        List<OutboxEventView> items = jdbcClient.sql("""
                select event_id,
                       aggregate_type,
                       aggregate_id,
                       event_type,
                       topic,
                       partition_key,
                       payload::text as payload_json,
                       status,
                       retry_count,
                       max_retry_count,
                       next_retry_at,
                       error_message,
                       created_at,
                       published_at
                from outbox_events
                where (:statusFilter = false or status in (:statuses))
                order by created_at desc, id desc
                limit :limit
                offset :offset
                """)
            .param("statusFilter", !statuses.isEmpty())
            .param("statuses", statusNames)
            .param("limit", limit)
            .param("offset", offset)
            .query(this::mapOutboxEvent)
            .list();

        return new OutboxEventPage(limit, offset, items);
    }

    private List<OutboxStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }

        return Arrays.stream(status.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(this::parseStatus)
            .toList();
    }

    private OutboxStatus parseStatus(String value) {
        try {
            return OutboxStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid outbox status: " + value, exception);
        }
    }

    private OutboxEventView mapOutboxEvent(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEventView(
            UUID.fromString(rs.getString("event_id")),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("topic"),
            rs.getString("partition_key"),
            rs.getString("payload_json"),
            parseStatus(rs.getString("status")),
            rs.getInt("retry_count"),
            rs.getInt("max_retry_count"),
            nullableInstant(rs, "next_retry_at"),
            rs.getString("error_message"),
            nullableInstant(rs, "created_at"),
            nullableInstant(rs, "published_at")
        );
    }

    private Instant nullableInstant(ResultSet rs, String columnName) throws SQLException {
        OffsetDateTime value = rs.getObject(columnName, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
