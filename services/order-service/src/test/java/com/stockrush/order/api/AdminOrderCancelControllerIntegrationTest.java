package com.stockrush.order.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class AdminOrderCancelControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from order_items").update();
        jdbcClient.sql("delete from customer_orders").update();
    }

    @Test
    void accepts_payment_delayed_order_cancel_request_and_writes_payment_cancel_command() throws Exception {
        insertOrder("ord_cancel_delay_001", "CREATED", "PAYMENT_DELAYED", "DELAY_CARD", "idem-cancel-delay-001");

        mockMvc.perform(post("/api/admin/orders/{orderId}/cancel", "ord_cancel_delay_001")
                .header("Idempotency-Key", "idem-admin-cancel-001")
                .header("X-Correlation-Id", "corr-admin-cancel-001"))
            .andExpect(status().isAccepted())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-cancel-001"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.orderId", is("ord_cancel_delay_001")))
            .andExpect(jsonPath("$.data.status", is("CREATED")))
            .andExpect(jsonPath("$.data.sagaStatus", is("PAYMENT_CANCEL_REQUESTED")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-cancel-001")));

        assertQuery("PAYMENT_CANCEL_REQUESTED", "select saga_status from customer_orders where order_id = 'ord_cancel_delay_001'");
        assertQuery("PaymentCancelRequested", "select event_type from outbox_events");
        assertQuery("stockrush.payment.commands.v1", "select topic from outbox_events");
        assertQuery("PENDING", "select status from outbox_events");
        assertQuery("ord_cancel_delay_001", "select payload ->> 'orderId' from outbox_events");
        assertQuery("ADMIN_CANCEL_REQUESTED", "select payload ->> 'reason' from outbox_events");
        assertQuery("idem-admin-cancel-001", "select idempotency_key from outbox_events");
        assertQuery("corr-admin-cancel-001", "select correlation_id from outbox_events");
    }

    @Test
    void returns_existing_cancel_request_without_writing_duplicate_command_when_retried() throws Exception {
        insertOrder("ord_cancel_retry_001", "CREATED", "PAYMENT_CANCEL_REQUESTED", "DELAY_CARD", "idem-cancel-retry-001");
        insertCancelRequestedOutbox("ord_cancel_retry_001", "idem-admin-cancel-retry-001");

        mockMvc.perform(post("/api/admin/orders/{orderId}/cancel", "ord_cancel_retry_001")
                .header("Idempotency-Key", "idem-admin-cancel-retry-002")
                .header("X-Correlation-Id", "corr-admin-cancel-retry-001"))
            .andExpect(status().isAccepted())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-cancel-retry-001"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.orderId", is("ord_cancel_retry_001")))
            .andExpect(jsonPath("$.data.status", is("CREATED")))
            .andExpect(jsonPath("$.data.sagaStatus", is("PAYMENT_CANCEL_REQUESTED")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-cancel-retry-001")));

        assertQuery("PAYMENT_CANCEL_REQUESTED", "select saga_status from customer_orders where order_id = 'ord_cancel_retry_001'");
        assertQuery("1", "select count(*)::text from outbox_events");
        assertQuery("idem-admin-cancel-retry-001", "select idempotency_key from outbox_events");
    }

    @Test
    void rejects_cancel_request_when_order_is_not_payment_delayed() throws Exception {
        insertOrder("ord_cancel_confirmed_001", "CONFIRMED", "COMPLETED", "CARD", "idem-cancel-confirmed-001");

        mockMvc.perform(post("/api/admin/orders/{orderId}/cancel", "ord_cancel_confirmed_001")
                .header("Idempotency-Key", "idem-admin-cancel-002")
                .header("X-Correlation-Id", "corr-admin-cancel-002"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-cancel-002"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_INVALID_REQUEST")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-cancel-002")));

        assertQuery("COMPLETED", "select saga_status from customer_orders where order_id = 'ord_cancel_confirmed_001'");
        assertQuery("0", "select count(*)::text from outbox_events");
    }

    @Test
    void rejects_cancel_request_with_blank_idempotency_key() throws Exception {
        insertOrder("ord_cancel_blank_key_001", "CREATED", "PAYMENT_DELAYED", "DELAY_CARD", "idem-cancel-blank-key-001");

        mockMvc.perform(post("/api/admin/orders/{orderId}/cancel", "ord_cancel_blank_key_001")
                .header("Idempotency-Key", " ")
                .header("X-Correlation-Id", "corr-admin-cancel-blank-key-001"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-cancel-blank-key-001"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_INVALID_REQUEST")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-cancel-blank-key-001")));

        assertQuery("PAYMENT_DELAYED", "select saga_status from customer_orders where order_id = 'ord_cancel_blank_key_001'");
        assertQuery("0", "select count(*)::text from outbox_events");
    }

    private void insertOrder(String orderId, String status, String sagaStatus, String paymentMethod, String idempotencyKey) {
        jdbcClient.sql("""
                insert into customer_orders (
                  order_id, member_id, status, saga_status, total_amount, discount_amount, payable_amount, payment_method,
                  idempotency_key, created_at, updated_at
                )
                values (
                  :orderId, 'member-cancel', :status, :sagaStatus, :totalAmount, 0.00, :totalAmount, :paymentMethod,
                  :idempotencyKey, now(), now()
                )
                """)
            .param("orderId", orderId)
            .param("status", status)
            .param("sagaStatus", sagaStatus)
            .param("totalAmount", new BigDecimal("24000.00"))
            .param("paymentMethod", paymentMethod)
            .param("idempotencyKey", idempotencyKey)
            .update();
    }

    private void insertCancelRequestedOutbox(String orderId, String idempotencyKey) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  gen_random_uuid(), 'order', :orderId, 'PaymentCancelRequested', 1,
                  'stockrush.payment.commands.v1', :orderId, 'corr-existing-cancel', :idempotencyKey,
                  jsonb_build_object('orderId', :orderId, 'reason', 'ADMIN_CANCEL_REQUESTED', 'requestedAt', now()),
                  '{}'::jsonb, 'PENDING', 0, 5, now(), now()
                )
                """)
            .param("orderId", orderId)
            .param("idempotencyKey", idempotencyKey)
            .update();
    }

    private void assertQuery(String expected, String sql) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, jdbcClient.sql(sql).query(String.class).single());
    }
}
