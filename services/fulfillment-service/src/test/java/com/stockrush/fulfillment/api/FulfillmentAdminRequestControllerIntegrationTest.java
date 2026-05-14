package com.stockrush.fulfillment.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=fulfillment",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class FulfillmentAdminRequestControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from fulfillment_requests").update();
        insertRequest(
            "018f8d0b-8d32-7c42-9f1b-78328e0f0701",
            "ord_fulfillment_admin_001",
            "PREPARING",
            "2026-05-13T08:10:00Z",
            "018f8d0b-8d32-7c42-9f1b-78328e0f07a1",
            "corr-fulfillment-admin-001",
            "idem-fulfillment-admin-001"
        );
        insertRequest(
            "018f8d0b-8d32-7c42-9f1b-78328e0f0702",
            "ord_fulfillment_admin_002",
            "PREPARING",
            "2026-05-13T08:12:00Z",
            "018f8d0b-8d32-7c42-9f1b-78328e0f07a2",
            "corr-fulfillment-admin-002",
            "idem-fulfillment-admin-002"
        );
        insertRequest(
            "018f8d0b-8d32-7c42-9f1b-78328e0f0703",
            "ord_fulfillment_admin_003",
            "PREPARING",
            "2026-05-13T08:14:00Z",
            "018f8d0b-8d32-7c42-9f1b-78328e0f07a3",
            "corr-fulfillment-admin-003",
            "idem-fulfillment-admin-003"
        );
    }

    @Test
    void lists_fulfillment_requests_for_admin_with_filters() throws Exception {
        mockMvc.perform(get("/api/admin/fulfillment-requests")
                .param("orderId", "ord_fulfillment_admin_002")
                .param("status", "PREPARING")
                .param("page", "0")
                .param("size", "20")
                .header("X-Correlation-Id", "corr-fulfillment-list"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-fulfillment-list"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.page", is(0)))
            .andExpect(jsonPath("$.data.size", is(20)))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].requestId", is("018f8d0b-8d32-7c42-9f1b-78328e0f0702")))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_fulfillment_admin_002")))
            .andExpect(jsonPath("$.data.items[0].status", is("PREPARING")))
            .andExpect(jsonPath("$.data.items[0].requestedAt", is("2026-05-13T08:12:00Z")))
            .andExpect(jsonPath("$.data.items[0].sourceEventId", is("018f8d0b-8d32-7c42-9f1b-78328e0f07a2")))
            .andExpect(jsonPath("$.data.items[0].correlationId", is("corr-fulfillment-admin-002")))
            .andExpect(jsonPath("$.data.items[0].idempotencyKey", is("idem-fulfillment-admin-002")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-fulfillment-list")));
    }

    @Test
    void normalizes_fulfillment_request_page_parameters() throws Exception {
        mockMvc.perform(get("/api/admin/fulfillment-requests")
                .param("page", "-1")
                .param("size", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page", is(0)))
            .andExpect(jsonPath("$.data.size", is(100)))
            .andExpect(jsonPath("$.data.items", hasSize(3)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_fulfillment_admin_003")));
    }

    @Test
    void accepts_trailing_slash_for_fulfillment_requests() throws Exception {
        mockMvc.perform(get("/api/admin/fulfillment-requests/")
                .param("status", "PREPARING")
                .header("X-Correlation-Id", "corr-fulfillment-slash"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-fulfillment-slash"))
            .andExpect(jsonPath("$.data.items", hasSize(3)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_fulfillment_admin_003")));
    }

    private void insertRequest(
        String requestId,
        String orderId,
        String status,
        String requestedAt,
        String sourceEventId,
        String correlationId,
        String idempotencyKey
    ) {
        jdbcClient.sql("""
                insert into fulfillment_requests (
                  request_id, order_id, status, requested_at, source_event_id,
                  correlation_id, idempotency_key, created_at, updated_at
                )
                values (
                  :requestId::uuid, :orderId, :status, :requestedAt::timestamptz,
                  :sourceEventId::uuid, :correlationId, :idempotencyKey,
                  :requestedAt::timestamptz, :requestedAt::timestamptz
                )
                """)
            .param("requestId", requestId)
            .param("orderId", orderId)
            .param("status", status)
            .param("requestedAt", requestedAt)
            .param("sourceEventId", sourceEventId)
            .param("correlationId", correlationId)
            .param("idempotencyKey", idempotencyKey)
            .update();
    }
}
