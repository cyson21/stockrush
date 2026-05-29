-- 도메인 초기화/보조 스키마 마이그레이션입니다.

package com.stockrush.payment.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.context.WebApplicationContext;

import com.stockrush.payment.infra.outbox.OutboxEventPublisher;
import com.stockrush.payment.infra.outbox.OutboxRelayEvent;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=payment",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class AdminOutboxEventsControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RecordingOutboxEventPublisher outboxEventPublisher;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from outbox_admin_actions").update();
        jdbcClient.sql("delete from outbox_events").update();
    }

    @Test
    void list_outbox_events_filters_by_status_and_sorts_by_created_desc() throws Exception {
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f9001", "PENDING", "2026-05-13T01:00:00Z", "PENDING-OLD");
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f9002", "FAILED", "2026-05-13T03:00:00Z", "FAILED-MID");
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f9003", "PENDING", "2026-05-13T04:00:00Z", "PENDING-NEW");
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f9004", "PUBLISHED", "2026-05-13T05:00:00Z", "PUBLISHED");

        mockMvc.perform(get("/api/admin/outbox-events")
                .param("status", "PENDING,FAILED")
                .param("limit", "2")
                .param("offset", "0")
                .header("X-Correlation-Id", "corr-outbox-list"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-outbox-list"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.limit", is(2)))
            .andExpect(jsonPath("$.data.offset", is(0)))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].eventId", is("018f8d0b-8d32-7c42-9f1b-78328e0f9003")))
            .andExpect(jsonPath("$.data.items[1].eventId", is("018f8d0b-8d32-7c42-9f1b-78328e0f9002")))
            .andExpect(jsonPath("$.data.items[0].aggregateId", is("PENDING-NEW")))
            .andExpect(jsonPath("$.data.items[1].aggregateId", is("FAILED-MID")))
            .andExpect(jsonPath("$.data.items[0].status", is("PENDING")))
            .andExpect(jsonPath("$.data.items[1].status", is("FAILED")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-outbox-list")));
    }

    @Test
    void retry_outbox_events_returns_publish_result() throws Exception {
        outboxEventPublisher.reset();
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f9101", "PENDING", "2026-05-13T06:00:00Z", "PAYMENT-A");
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f9102", "PENDING", "2026-05-13T06:01:00Z", "PAYMENT-B");

        mockMvc.perform(post("/api/admin/outbox-events/retry")
                .param("batchSize", "10")
                .header("X-Correlation-Id", "corr-outbox-retry")
                .header("X-Operator-Id", "operator-payment"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-outbox-retry"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.claimed", is(2)))
            .andExpect(jsonPath("$.data.published", is(2)))
            .andExpect(jsonPath("$.data.failed", is(0)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-outbox-retry")));

        assertEquals(2, outboxEventPublisher.publishCount());
        assertEquals(
            1,
            queryInt("""
                select count(*)
                from outbox_admin_actions
                where action = 'RETRY_PENDING'
                  and requested_batch_size = 10
                  and affected_count = 2
                  and operator_id = 'operator-payment'
                  and correlation_id = 'corr-outbox-retry'
                """)
        );
    }

    @Test
    void requeue_failed_outbox_events_for_manual_retry() throws Exception {
        insertOutboxEvent("018f8d0b-8d32-7c42-9f1b-78328e0f9103", "FAILED", "2026-05-13T06:02:00Z", "PAYMENT-C");
        jdbcClient.sql("""
                update outbox_events
                set retry_count = 5,
                    next_retry_at = now(),
                    error_message = 'kafka unavailable'
                where event_id = cast(:eventId as uuid)
                """)
            .param("eventId", "018f8d0b-8d32-7c42-9f1b-78328e0f9103")
            .update();

        mockMvc.perform(post("/api/admin/outbox-events/failed/requeue")
                .param("batchSize", "10")
                .header("X-Correlation-Id", "corr-outbox-requeue")
                .header("X-Operator-Id", "operator-payment"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-outbox-requeue"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.updated", is(1)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-outbox-requeue")));

        assertEquals("PENDING", queryString("select status from outbox_events"));
        assertEquals(0, queryInt("select retry_count from outbox_events"));
        assertEquals(1, queryInt("select count(*) from outbox_events where next_retry_at is null"));
        assertEquals(1, queryInt("select count(*) from outbox_events where error_message is null"));
        assertEquals(
            1,
            queryInt("""
                select count(*)
                from outbox_admin_actions
                where action = 'REQUEUE_FAILED'
                  and requested_batch_size = 10
                  and affected_count = 1
                  and operator_id = 'operator-payment'
                  and correlation_id = 'corr-outbox-requeue'
                """)
        );
    }

    @Test
    void reject_invalid_status_filter_as_bad_request() throws Exception {
        mockMvc.perform(get("/api/admin/outbox-events")
                .param("status", "INVALID_STATUS")
                .header("X-Correlation-Id", "corr-outbox-invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-outbox-invalid"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("PAYMENT_INVALID_REQUEST")));
    }

    private void insertOutboxEvent(String eventId, String status, String createdAt, String aggregateId) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  cast(:eventId as uuid), 'payment', :aggregateId, 'PaymentAuthorized', 1,
                  'stockrush.payment.events.v1', :aggregateId, 'corr-outbox', 'idem-outbox', jsonb_build_object('orderId', :orderId), '{}'::jsonb,
                  :status, 0, 5, :createdAt::timestamptz, :createdAt::timestamptz
                )
                """)
            .param("eventId", eventId)
            .param("aggregateId", aggregateId)
            .param("orderId", aggregateId)
            .param("status", status)
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
    static class TestOutboxPublisherConfig {

        @Bean
        @Primary
        RecordingOutboxEventPublisher recordingOutboxEventPublisher() {
            return new RecordingOutboxEventPublisher();
        }
    }

    static class RecordingOutboxEventPublisher implements OutboxEventPublisher {

        private int publishCount;

        @Override
        public void publish(OutboxRelayEvent event) {
            publishCount++;
        }

        void reset() {
            publishCount = 0;
        }

        int publishCount() {
            return publishCount;
        }
    }
}
