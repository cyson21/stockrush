// OutboxRelayServiceIntegrationTest: 비즈니스 핵심 흐름을 조합해 상태 변경과 유효성 규칙을 적용합니다.

package com.stockrush.order.infra.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=orders",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class OutboxRelayServiceIntegrationTest {

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private RecordingOutboxEventPublisher publisher;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        publisher.reset();
        jdbcClient.sql("delete from outbox_events").update();
    }

    @Test
    void publishes_pending_event_and_marks_published() {
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f7a31", "PENDING", 0, 5);

        OutboxRelayResult result = relayService.publishPending(10);

        assertEquals(1, result.claimed());
        assertEquals(1, result.published());
        assertEquals(0, result.failed());
        assertEquals(1, publisher.events.size());
        assertEquals("stockrush.order.events.v1", publisher.events.get(0).topic());
        assertEquals("ord_relay_001", publisher.events.get(0).partitionKey());
        assertEquals("PUBLISHED", queryString("select status from outbox_events"));
        assertNotNull(queryString("select published_at::text from outbox_events"));
    }

    @Test
    void schedules_retry_when_publish_fails_before_max_retry_count() {
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f7a32", "PENDING", 1, 5);
        publisher.failNext = true;

        OutboxRelayResult result = relayService.publishPending(10);

        assertEquals(1, result.claimed());
        assertEquals(0, result.published());
        assertEquals(1, result.failed());
        assertEquals("PENDING", queryString("select status from outbox_events"));
        assertEquals(2, queryInt("select retry_count from outbox_events"));
        assertNotNull(queryString("select next_retry_at::text from outbox_events"));
        assertEquals("temporary kafka failure", queryString("select error_message from outbox_events"));
    }

    @Test
    void marks_failed_when_publish_retry_count_is_exhausted() {
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f7a33", "PENDING", 4, 5);
        publisher.failNext = true;

        OutboxRelayResult result = relayService.publishPending(10);

        assertEquals(1, result.claimed());
        assertEquals(0, result.published());
        assertEquals(1, result.failed());
        assertEquals("FAILED", queryString("select status from outbox_events"));
        assertEquals(5, queryInt("select retry_count from outbox_events"));
        assertNull(queryString("select next_retry_at::text from outbox_events"));
        assertEquals("temporary kafka failure", queryString("select error_message from outbox_events"));
    }

    private void insertOutboxEvent(String eventId, String status, int retryCount, int maxRetryCount) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  cast(:eventId as uuid), 'order', 'ord_relay_001', 'OrderCreated', 1,
                  'stockrush.order.events.v1', 'ord_relay_001', 'corr-relay-001', 'idem-relay-001',
                  '{"orderId": "ord_relay_001"}'::jsonb, '{}'::jsonb,
                  :status, :retryCount, :maxRetryCount, now(), now()
                )
                """)
            .param("eventId", eventId)
            .param("status", status)
            .param("retryCount", retryCount)
            .param("maxRetryCount", maxRetryCount)
            .update();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).optional().orElse(null);
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    @TestConfiguration
    static class PublisherConfig {

        @Bean
        @Primary
        RecordingOutboxEventPublisher recordingOutboxEventPublisher() {
            return new RecordingOutboxEventPublisher();
        }
    }

    static class RecordingOutboxEventPublisher implements OutboxEventPublisher {

        private final List<OutboxRelayEvent> events = new ArrayList<>();
        private boolean failNext;

        @Override
        public void publish(OutboxRelayEvent event) {
            if (failNext) {
                failNext = false;
                throw new OutboxPublishException("temporary kafka failure");
            }
            events.add(event);
        }

        void reset() {
            events.clear();
            failNext = false;
        }
    }
}
