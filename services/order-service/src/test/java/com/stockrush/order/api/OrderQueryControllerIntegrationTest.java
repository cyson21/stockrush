package com.stockrush.order.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=orders",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class OrderQueryControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from order_items").update();
        jdbcClient.sql("delete from customer_orders").update();
        insertOrder();
    }

    @Test
    void returns_order_detail_with_items() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", "ord_query_001")
                .header("X-Correlation-Id", "corr-order-query"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-order-query"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.orderId", is("ord_query_001")))
            .andExpect(jsonPath("$.data.memberId", is("member-query-1")))
            .andExpect(jsonPath("$.data.status", is("CONFIRMED")))
            .andExpect(jsonPath("$.data.sagaStatus", is("COMPLETED")))
            .andExpect(jsonPath("$.data.paymentMethod", is("CARD")))
            .andExpect(jsonPath("$.data.totalAmount", is(29000.00)))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].skuId", is("SKU-001")))
            .andExpect(jsonPath("$.data.items[0].lineAmount", is(24000.00)))
            .andExpect(jsonPath("$.data.items[1].skuId", is("SKU-002")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-order-query")));
    }

    @Test
    void returns_not_found_for_unknown_order() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", "ord_missing")
                .header("X-Correlation-Id", "corr-order-missing"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Correlation-Id", "corr-order-missing"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_NOT_FOUND")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-order-missing")));
    }

    @Test
    void returns_data_integrity_error_for_invalid_persisted_status() throws Exception {
        jdbcClient.sql("""
                insert into customer_orders (
                  order_id, member_id, status, saga_status, total_amount, payment_method,
                  idempotency_key, created_at, updated_at
                )
                values (
                  'ord_invalid_status', 'member-query-1', 'BROKEN', 'COMPLETED', 1000.00, 'CARD',
                  'idem-invalid-status', now(), now()
                )
                """)
            .update();

        mockMvc.perform(get("/api/orders/{orderId}", "ord_invalid_status")
                .header("X-Correlation-Id", "corr-order-data-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("X-Correlation-Id", "corr-order-data-error"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_DATA_INTEGRITY_ERROR")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-order-data-error")));
    }

    private void insertOrder() {
        jdbcClient.sql("""
                insert into customer_orders (
                  order_id, member_id, status, saga_status, total_amount, payment_method,
                  idempotency_key, created_at, updated_at
                )
                values (
                  'ord_query_001', 'member-query-1', 'CONFIRMED', 'COMPLETED', 29000.00, 'CARD',
                  'idem-query-001', now(), now()
                )
                """)
            .update();
        insertItem("LIMITED-001", "SKU-001", 2, "12000.00");
        insertItem("LIMITED-002", "SKU-002", 1, "5000.00");
    }

    private void insertItem(String productCode, String skuId, int quantity, String unitPrice) {
        jdbcClient.sql("""
                insert into order_items (order_id, product_code, sku_id, quantity, unit_price, created_at)
                values ('ord_query_001', :productCode, :skuId, :quantity, :unitPrice, now())
                """)
            .param("productCode", productCode)
            .param("skuId", skuId)
            .param("quantity", quantity)
            .param("unitPrice", new BigDecimal(unitPrice))
            .update();
    }
}
