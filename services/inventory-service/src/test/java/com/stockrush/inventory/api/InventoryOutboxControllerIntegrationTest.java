package com.stockrush.inventory.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockrush.inventory.infra.outbox.OutboxEventPublisher;
import com.stockrush.inventory.infra.outbox.OutboxRelayEvent;
import java.time.OffsetDateTime;
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
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=inventory",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class InventoryOutboxControllerIntegrationTest {

    private static final String CORRELATION_ID = "corr-admin-outbox";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from outbox_events").update();
    }

    @Test
    void lists_outbox_events_filtered_by_status_and_sorted_by_created_at_desc() throws Exception {
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f8b01",
            "PENDING",
            OffsetDateTime.parse("2026-05-13T10:00:00Z")
        );
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f8b02",
            "FAILED",
            OffsetDateTime.parse("2026-05-13T11:00:00Z")
        );
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f8b03",
            "PUBLISHED",
            OffsetDateTime.parse("2026-05-13T12:00:00Z")
        );

        mockMvc.perform(get("/api/admin/outbox-events")
                .param("status", "PENDING,FAILED")
                .param("limit", "2")
                .param("offset", "0")
                .header("X-Correlation-Id", CORRELATION_ID))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.limit", is(2)))
            .andExpect(jsonPath("$.data.offset", is(0)))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].eventId", is("018f8d0b-8d32-7c42-9f1b-78328e0f8b02")))
            .andExpect(jsonPath("$.data.items[0].status", is("FAILED")))
            .andExpect(jsonPath("$.data.items[1].eventId", is("018f8d0b-8d32-7c42-9f1b-78328e0f8b01")))
            .andExpect(jsonPath("$.data.items[1].status", is("PENDING")));
    }

    @Test
    void returns_retry_result_for_outbox_publish_batch() throws Exception {
        insertOutboxEvent(
            "018f8d0b-8d32-7c42-9f1b-78328e0f8b04",
            "PENDING",
            OffsetDateTime.parse("2026-05-13T10:00:00Z")
        );

        mockMvc.perform(post("/api/admin/outbox-events/retry")
                .param("batchSize", "10")
                .header("X-Correlation-Id", CORRELATION_ID))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.claimed", is(1)))
            .andExpect(jsonPath("$.data.published", is(1)))
            .andExpect(jsonPath("$.data.failed", is(0)))
            .andExpect(jsonPath("$.trace.correlationId", is(CORRELATION_ID)));
    }

    @Test
    void rejects_invalid_status_filter_values() throws Exception {
        mockMvc.perform(get("/api/admin/outbox-events")
                .param("status", "PENDING,UNKNOWN")
                .header("X-Correlation-Id", CORRELATION_ID))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("INVENTORY_INVALID_REQUEST")))
            .andExpect(jsonPath("$.trace.correlationId", is(CORRELATION_ID)));
    }

    private void insertOutboxEvent(String eventId, String status, OffsetDateTime createdAt) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  cast(:eventId as uuid), 'inventory-reservation', 'ord_inventory_001', 'InventoryReserved', 1,
                  'stockrush.inventory.events.v1', 'ord_inventory_001', 'corr-test', 'idem-test',
                  '{"orderId":"ord_inventory_001"}'::jsonb, '{}'::jsonb,
                  :status, 0, 5, :createdAt, :createdAt
                )
                """)
            .param("eventId", eventId)
            .param("status", status)
            .param("createdAt", createdAt)
            .update();
    }

    @TestConfiguration
    static class OutboxEventPublisherConfig {

        @Bean
        @Primary
        OutboxEventPublisher outboxEventPublisher() {
            return new OutboxEventPublisher() {
                @Override
                public void publish(OutboxRelayEvent event) {
                    // no-op for admin controller integration tests
                }
            };
        }
    }
}
