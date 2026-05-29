// JdbcOutboxEventRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.order.infra.persistence;

import static com.stockrush.order.infra.persistence.JdbcTimestamps.timestampWithTimeZone;

import com.stockrush.order.application.OutboxEventRecord;
import com.stockrush.order.application.OutboxEventRepository;
import java.sql.Types;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
class JdbcOutboxEventRepository implements OutboxEventRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcOutboxEventRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(OutboxEventRecord<?> event) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  :eventId, :aggregateType, :aggregateId, :eventType, :eventVersion,
                  :topic, :partitionKey, :correlationId, :idempotencyKey, :payload, :headers,
                  :status, 0, 5, :createdAt, :updatedAt
                )
                """)
            .param("eventId", event.eventId())
            .param("aggregateType", event.aggregateType())
            .param("aggregateId", event.aggregateId())
            .param("eventType", event.eventType())
            .param("eventVersion", event.eventVersion())
            .param("topic", event.topic())
            .param("partitionKey", event.partitionKey())
            .param("correlationId", event.correlationId())
            .param("idempotencyKey", event.idempotencyKey())
            .param("payload", jsonb(writePayload(event)))
            .param("headers", jsonb(writeHeaders(event)))
            .param("status", event.status().name())
            .param("createdAt", timestampWithTimeZone(event.occurredAt()))
            .param("updatedAt", timestampWithTimeZone(event.occurredAt()))
            .update();
    }

    private String writePayload(OutboxEventRecord<?> event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to serialize outbox payload", e);
        }
    }

    private String writeHeaders(OutboxEventRecord<?> event) {
        try {
            ObjectNode headers = objectMapper.createObjectNode();
            if (event.causationId() == null) {
                headers.putNull("causationId");
            } else {
                headers.put("causationId", event.causationId().toString());
            }
            headers.put("sourceService", event.sourceService());
            return objectMapper.writeValueAsString(headers);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to serialize outbox headers", e);
        }
    }

    private SqlParameterValue jsonb(String json) {
        return new SqlParameterValue(Types.OTHER, json);
    }
}
