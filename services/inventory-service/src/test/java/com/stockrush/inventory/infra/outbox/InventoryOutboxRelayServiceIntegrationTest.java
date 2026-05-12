package com.stockrush.inventory.infra.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=inventory",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class InventoryOutboxRelayServiceIntegrationTest {

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private RecordingOutboxEventPublisher publisher;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        publisher.reset();
        jdbcClient.sql("delete from outbox_events").update();
    }

    @Test
    void publishes_pending_event_and_marks_published() {
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f8a01", "PENDING", 0, 5);

        OutboxRelayResult result = relayService.publishPending(10);

        assertEquals(1, result.claimed());
        assertEquals(1, result.published());
        assertEquals(0, result.failed());
        assertEquals(1, publisher.events.size());
        assertEquals("stockrush.inventory.events.v1", publisher.events.get(0).topic());
        assertEquals("ord_inventory_relay_001", publisher.events.get(0).partitionKey());
        assertEquals("PUBLISHED", queryString("select status from outbox_events"));
        assertNotNull(queryString("select published_at::text from outbox_events"));
    }

    @Test
    void schedules_retry_when_publish_fails_before_max_retry_count() {
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f8a02", "PENDING", 1, 5);
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
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f8a03", "PENDING", 4, 5);
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

    @Test
    void kafka_publisher_sends_inventory_envelope_json_with_default_headers() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateMock();
        when(kafkaTemplate.send(eq("stockrush.inventory.events.v1"), eq("ord_inventory_relay_001"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f8a04", "PENDING", 0, 5);
        OutboxRelayEvent event = relayService.publishPending(1).claimed() == 1
            ? publisher.events.get(0)
            : null;

        new KafkaOutboxEventPublisher(kafkaTemplate, objectMapper).publish(event);

        org.mockito.ArgumentCaptor<String> envelopeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
            eq("stockrush.inventory.events.v1"),
            eq("ord_inventory_relay_001"),
            envelopeCaptor.capture()
        );
        JsonNode envelope = objectMapper.readTree(envelopeCaptor.getValue());
        assertEquals("018f8d0b-8d32-7c42-9f1b-78328e0f8a04", envelope.path("eventId").stringValue());
        assertEquals("InventoryReserved", envelope.path("eventType").stringValue());
        assertEquals(1, envelope.path("eventVersion").intValue());
        assertEquals("inventory-reservation", envelope.path("aggregateType").stringValue());
        assertEquals("ord_inventory_relay_001", envelope.path("aggregateId").stringValue());
        assertEquals("corr-inventory-relay-001", envelope.path("correlationId").stringValue());
        assertTrue(envelope.path("causationId").isNull());
        assertEquals("idem-inventory-relay-001", envelope.path("idempotencyKey").stringValue());
        assertNotNull(envelope.path("occurredAt").stringValue(null));
        assertEquals("inventory-service", envelope.path("sourceService").stringValue());
        assertEquals("ord_inventory_relay_001", envelope.path("payload").path("orderId").stringValue());
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplateMock() {
        return mock(KafkaTemplate.class);
    }

    private void insertOutboxEvent(String eventId, String status, int retryCount, int maxRetryCount) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  cast(:eventId as uuid), 'inventory-reservation', 'ord_inventory_relay_001', 'InventoryReserved', 1,
                  'stockrush.inventory.events.v1', 'ord_inventory_relay_001', 'corr-inventory-relay-001', 'idem-inventory-relay-001',
                  '{"orderId": "ord_inventory_relay_001"}'::jsonb, '{}'::jsonb,
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
