package com.stockrush.readmodel.api;

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
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=read_model",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class OrderReadModelControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from order_summaries").update();
        insertSummary("ord_read_api_old", "member-api-1", "CREATED", "STARTED", "2026-05-14T00:01:00Z");
        insertSummary("ord_read_api_new", "member-api-1", "CONFIRMED", "COMPLETED", "2026-05-14T00:03:00Z");
        insertSummary("ord_read_api_other", "member-api-2", "CANCELLED", "FAILED", "2026-05-14T00:04:00Z");
    }

    @Test
    void lists_customer_order_history_from_projection() throws Exception {
        mockMvc.perform(get("/api/read-model/orders")
                .param("memberId", "member-api-1")
                .param("size", "5")
                .header("X-Correlation-Id", "corr-read-customer"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-read-customer"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_read_api_new")))
            .andExpect(jsonPath("$.data.items[0].status", is("CONFIRMED")))
            .andExpect(jsonPath("$.data.items[1].orderId", is("ord_read_api_old")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-read-customer")));
    }

    @Test
    void normalizes_customer_order_history_page_parameters() throws Exception {
        mockMvc.perform(get("/api/read-model/orders")
                .param("memberId", "member-api-1")
                .param("page", "-1")
                .param("size", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page", is(0)))
            .andExpect(jsonPath("$.data.size", is(100)))
            .andExpect(jsonPath("$.data.items", hasSize(2)));
    }

    @Test
    void lists_admin_orders_with_status_filter_from_projection() throws Exception {
        mockMvc.perform(get("/api/read-model/admin/orders")
                .param("status", "CANCELLED")
                .header("X-Correlation-Id", "corr-read-admin"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-read-admin"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_read_api_other")))
            .andExpect(jsonPath("$.data.items[0].sagaStatus", is("FAILED")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-read-admin")));
    }

    private void insertSummary(String orderId, String memberId, String status, String sagaStatus, String createdAt) {
        jdbcClient.sql("""
                insert into order_summaries (
                  order_id, member_id, status, saga_status, coupon_code, total_amount,
                  discount_amount, payable_amount, item_count, created_at, updated_at
                )
                values (
                  :orderId, :memberId, :status, :sagaStatus, null, 30000.00,
                  0.00, 30000.00, 1, :createdAt::timestamptz, :createdAt::timestamptz
                )
                """)
            .param("orderId", orderId)
            .param("memberId", memberId)
            .param("status", status)
            .param("sagaStatus", sagaStatus)
            .param("createdAt", createdAt)
            .update();
    }
}
