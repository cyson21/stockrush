package com.stockrush.order.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockrush.order.infra.outbox.OutboxEventPublisher;
import com.stockrush.order.infra.outbox.OutboxRelayEvent;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=orders",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class OutboxAdminControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RecordingOutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        publisher.reset();
        jdbcClient.sql("delete from outbox_admin_actions").update();
        jdbcClient.sql("delete from outbox_events").update();
    }

    @Test
    void lists_outbox_events_by_status_sorted_desc() throws Exception {
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f7b01",
            "PENDING",
            "OrderCreated",
            0,
            null,
            "2026-05-13T01:00:00Z"
        );
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f7b02",
            "FAILED",
            "OrderCancelled",
            5,
            "kafka unavailable",
            "2026-05-13T03:00:00Z"
        );
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f7b03",
            "PUBLISHED",
            "OrderConfirmed",
            1,
            null,
            "2026-05-13T02:00:00Z"
        );

        mockMvc.perform(get("/api/admin/outbox-events")
                .param("status", "PENDING,FAILED")
                .param("limit", "5")
                .param("offset", "0")
                .header("X-Correlation-Id", "corr-outbox-list"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-outbox-list"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.limit", is(5)))
            .andExpect(jsonPath("$.data.offset", is(0)))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].eventType", is("OrderCancelled")))
            .andExpect(jsonPath("$.data.items[0].status", is("FAILED")))
            .andExpect(jsonPath("$.data.items[0].retryCount", is(5)))
            .andExpect(jsonPath("$.data.items[0].errorMessage", is("kafka unavailable")))
            .andExpect(jsonPath("$.data.items[1].eventType", is("OrderCreated")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-outbox-list")));
    }

    @Test
    void retries_pending_outbox_events() throws Exception {
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f7b04",
            "PENDING",
            "OrderCreated",
            0,
            null,
            "2026-05-13T01:00:00Z"
        );

        mockMvc.perform(post("/api/admin/outbox-events/retry")
                .param("batchSize", "10")
                .header("X-Correlation-Id", "corr-outbox-retry")
                .header("X-Operator-Id", "operator-order"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-outbox-retry"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.claimed", is(1)))
            .andExpect(jsonPath("$.data.published", is(1)))
            .andExpect(jsonPath("$.data.failed", is(0)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-outbox-retry")));

        org.junit.jupiter.api.Assertions.assertEquals(1, publisher.events.size());
        org.junit.jupiter.api.Assertions.assertEquals("PUBLISHED", queryString("select status from outbox_events"));
        org.junit.jupiter.api.Assertions.assertEquals(
            1,
            queryInt("""
                select count(*)
                from outbox_admin_actions
                where action = 'RETRY_PENDING'
                  and requested_batch_size = 10
                  and affected_count = 1
                  and operator_id = 'operator-order'
                  and correlation_id = 'corr-outbox-retry'
                """)
        );
    }

    @Test
    void requeues_failed_outbox_events_for_manual_retry() throws Exception {
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f7b05",
            "FAILED",
            "OrderCancelled",
            5,
            "kafka unavailable",
            "2026-05-13T03:00:00Z"
        );
        jdbcClient.sql("update outbox_events set next_retry_at = now() where event_type = 'OrderCancelled'").update();

        mockMvc.perform(post("/api/admin/outbox-events/failed/requeue")
                .param("batchSize", "10")
                .header("X-Correlation-Id", "corr-outbox-requeue")
                .header("X-Operator-Id", "operator-order"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-outbox-requeue"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.updated", is(1)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-outbox-requeue")));

        org.junit.jupiter.api.Assertions.assertEquals("PENDING", queryString("select status from outbox_events"));
        org.junit.jupiter.api.Assertions.assertEquals(0, queryInt("select retry_count from outbox_events"));
        org.junit.jupiter.api.Assertions.assertEquals(1, queryInt("select count(*) from outbox_events where next_retry_at is null"));
        org.junit.jupiter.api.Assertions.assertEquals(1, queryInt("select count(*) from outbox_events where error_message is null"));
        org.junit.jupiter.api.Assertions.assertEquals(
            1,
            queryInt("""
                select count(*)
                from outbox_admin_actions
                where action = 'REQUEUE_FAILED'
                  and requested_batch_size = 10
                  and affected_count = 1
                  and operator_id = 'operator-order'
                  and correlation_id = 'corr-outbox-requeue'
                """)
        );
    }

    private void insertOutboxEvent(
        String eventId,
        String status,
        String eventType,
        int retryCount,
        String errorMessage,
        String createdAt
    ) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, error_message, created_at, updated_at
                )
                values (
                  cast(:eventId as uuid), 'order', 'ord_admin_outbox', :eventType, 1,
                  'stockrush.order.events.v1', 'ord_admin_outbox', 'corr-outbox', 'idem-outbox',
                  '{"orderId": "ord_admin_outbox"}'::jsonb, '{}'::jsonb,
                  :status, :retryCount, 5, :errorMessage, :createdAt::timestamptz, :createdAt::timestamptz
                )
                """)
            .param("eventId", eventId)
            .param("eventType", eventType)
            .param("status", status)
            .param("retryCount", retryCount)
            .param("errorMessage", errorMessage)
            .param("createdAt", createdAt)
            .update();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
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

        @Override
        public void publish(OutboxRelayEvent event) {
            events.add(event);
        }

        void reset() {
            events.clear();
        }
    }
}
