package com.stockrush.payment.infra.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OutboxRelayService {

    private static final int RETRY_BACKOFF_SECONDS = 30;

    private final JdbcClient jdbcClient;
    private final ObjectProvider<OutboxEventPublisher> publisherProvider;

    public OutboxRelayService(JdbcClient jdbcClient, ObjectProvider<OutboxEventPublisher> publisherProvider) {
        this.jdbcClient = jdbcClient;
        this.publisherProvider = publisherProvider;
    }

    public OutboxRelayResult publishPending(int batchSize) {
        OutboxEventPublisher publisher = publisherProvider.getIfAvailable(() -> {
            throw new IllegalStateException("outbox event publisher is not configured");
        });
        List<OutboxRelayEvent> events = claimPending(batchSize);
        int published = 0;
        int failed = 0;

        for (OutboxRelayEvent event : events) {
            try {
                publisher.publish(event);
                markPublished(event);
                published++;
            } catch (RuntimeException e) {
                markPublishFailed(event, e);
                failed++;
            }
        }

        return new OutboxRelayResult(events.size(), published, failed);
    }

    private List<OutboxRelayEvent> claimPending(int batchSize) {
        return jdbcClient.sql("""
                with claimed as (
                  select id
                  from outbox_events
                  where status = 'PENDING'
                    and (next_retry_at is null or next_retry_at <= now())
                  order by created_at
                  limit :batchSize
                  for update skip locked
                )
                update outbox_events outbox
                set status = 'PUBLISHING',
                    updated_at = now()
                from claimed
                where outbox.id = claimed.id
                returning outbox.id,
                          outbox.event_id,
                          outbox.aggregate_type,
                          outbox.aggregate_id,
                          outbox.event_type,
                          outbox.event_version,
                          outbox.topic,
                          outbox.partition_key,
                          outbox.correlation_id,
                          outbox.idempotency_key,
                          outbox.payload::text as payload_json,
                          outbox.headers::text as headers_json,
                          outbox.retry_count,
                          outbox.max_retry_count,
                          outbox.created_at
                """)
            .param("batchSize", batchSize)
            .query(this::mapRelayEvent)
            .list();
    }

    private void markPublished(OutboxRelayEvent event) {
        jdbcClient.sql("""
                update outbox_events
                set status = 'PUBLISHED',
                    published_at = now(),
                    next_retry_at = null,
                    error_message = null,
                    updated_at = now()
                where id = :id
                  and status = 'PUBLISHING'
                """)
            .param("id", event.id())
            .update();
    }

    private void markPublishFailed(OutboxRelayEvent event, RuntimeException exception) {
        boolean exhausted = event.retryCount() + 1 >= event.maxRetryCount();
        String nextStatus = exhausted ? "FAILED" : "PENDING";

        jdbcClient.sql("""
                update outbox_events
                set status = :status,
                    retry_count = retry_count + 1,
                    next_retry_at = case
                      when :exhausted then null
                      else now() + (:backoffSeconds * interval '1 second')
                    end,
                    error_message = :errorMessage,
                    updated_at = now()
                where id = :id
                  and status = 'PUBLISHING'
                """)
            .param("status", nextStatus)
            .param("exhausted", exhausted)
            .param("backoffSeconds", RETRY_BACKOFF_SECONDS)
            .param("errorMessage", exception.getMessage())
            .param("id", event.id())
            .update();
    }

    private OutboxRelayEvent mapRelayEvent(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRelayEvent(
            rs.getLong("id"),
            UUID.fromString(rs.getString("event_id")),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getInt("event_version"),
            rs.getString("topic"),
            rs.getString("partition_key"),
            rs.getString("correlation_id"),
            rs.getString("idempotency_key"),
            rs.getString("payload_json"),
            rs.getString("headers_json"),
            rs.getInt("retry_count"),
            rs.getInt("max_retry_count"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }
}
