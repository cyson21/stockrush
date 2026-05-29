// JdbcOutboxEventRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.inventory.infra.persistence;

import com.stockrush.inventory.application.OutboxEventRepository;
import com.stockrush.inventory.application.OutboxEventSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxEventRepository implements OutboxEventRepository {

    private final JdbcClient jdbcClient;

    JdbcOutboxEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<OutboxEventSnapshot> findOutboxEvents(int limit, int offset, List<String> statuses) {
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
                       published_at,
                       next_retry_at,
                       error_message
                from outbox_events
                """);

        if (!statuses.isEmpty()) {
            sql.append(" where status in (:status)\n");
        }
        sql.append(" order by created_at desc limit :limit offset :offset");

        var query = jdbcClient.sql(sql.toString())
            .param("limit", limit)
            .param("offset", offset);

        if (!statuses.isEmpty()) {
            query = query.param("status", statuses);
        }

        return query.query(this::mapOutboxEvent).list();
    }

    private OutboxEventSnapshot mapOutboxEvent(ResultSet resultSet, int rowNum) throws SQLException {
        return new OutboxEventSnapshot(
            resultSet.getLong("id"),
            resultSet.getObject("event_id").toString(),
            resultSet.getString("aggregate_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getString("event_type"),
            resultSet.getInt("event_version"),
            resultSet.getString("topic"),
            resultSet.getString("partition_key"),
            resultSet.getString("correlation_id"),
            resultSet.getString("idempotency_key"),
            resultSet.getString("payload_json"),
            resultSet.getString("status"),
            resultSet.getInt("retry_count"),
            resultSet.getInt("max_retry_count"),
            toInstant(resultSet, "created_at"),
            toInstant(resultSet, "published_at"),
            toInstant(resultSet, "next_retry_at"),
            resultSet.getString("error_message")
        );
    }

    private Instant toInstant(ResultSet resultSet, String columnName) throws SQLException {
        OffsetDateTime value = resultSet.getObject(columnName, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
