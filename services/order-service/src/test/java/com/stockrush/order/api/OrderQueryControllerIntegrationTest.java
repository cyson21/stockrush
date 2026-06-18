// OrderQueryControllerIntegrationTest: API 진입점으로 요청/응답 경계와 HTTP 흐름을 정리합니다.

package com.stockrush.order.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
                .header("X-StockRush-Subject", "member-query-1")
                .header("X-Correlation-Id", "corr-order-query"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-order-query"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.orderId", is("ord_query_001")))
            .andExpect(jsonPath("$.data.memberId", is("member-query-1")))
            .andExpect(jsonPath("$.data.status", is("CONFIRMED")))
            .andExpect(jsonPath("$.data.sagaStatus", is("COMPLETED")))
            .andExpect(jsonPath("$.data.paymentMethod", is("CARD")))
            .andExpect(jsonPath("$.data.couponCode", is("WELCOME10")))
            .andExpect(jsonPath("$.data.totalAmount", is(29000.00)))
            .andExpect(jsonPath("$.data.discountAmount", is(5000.00)))
            .andExpect(jsonPath("$.data.payableAmount", is(24000.00)))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].skuId", is("SKU-001")))
            .andExpect(jsonPath("$.data.items[0].lineAmount", is(24000.00)))
            .andExpect(jsonPath("$.data.items[1].skuId", is("SKU-002")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-order-query")));
    }

    @Test
    void returns_order_detail_for_matching_authenticated_subject() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", "ord_query_001")
                .header("X-StockRush-Subject", "member-query-1")
                .header("X-Correlation-Id", "corr-order-auth-query"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-order-auth-query"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.orderId", is("ord_query_001")))
            .andExpect(jsonPath("$.data.memberId", is("member-query-1")));
    }

    @Test
    void rejects_order_detail_without_trusted_customer_subject() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", "ord_query_001")
                .header("X-Correlation-Id", "corr-order-missing-subject"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Correlation-Id", "corr-order-missing-subject"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_TRUSTED_IDENTITY_REQUIRED")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-order-missing-subject")));
    }

    @Test
    void rejects_order_detail_for_different_authenticated_subject() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", "ord_query_001")
                .header("X-StockRush-Subject", "member-other")
                .header("X-Correlation-Id", "corr-order-forbidden"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("X-Correlation-Id", "corr-order-forbidden"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_FORBIDDEN")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-order-forbidden")));
    }

    @Test
    void returns_not_found_for_unknown_order() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", "ord_missing")
                .header("X-StockRush-Subject", "member-query-1")
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
                  order_id, member_id, status, saga_status, total_amount, discount_amount, payable_amount, payment_method,
                  idempotency_key, created_at, updated_at
                )
                values (
                  'ord_invalid_status', 'member-query-1', 'BROKEN', 'COMPLETED', 1000.00, 0.00, 1000.00, 'CARD',
                  'idem-invalid-status', now(), now()
                )
                """)
            .update();

        mockMvc.perform(get("/api/orders/{orderId}", "ord_invalid_status")
                .header("X-StockRush-Subject", "member-query-1")
                .header("X-Correlation-Id", "corr-order-data-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("X-Correlation-Id", "corr-order-data-error"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_DATA_INTEGRITY_ERROR")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-order-data-error")));
    }

    @Test
    void lists_recent_admin_orders_sorted_desc() throws Exception {
        insertOrder("ord_admin_old", "member-old", "CREATED", "STARTED", "CARD", "8000.00", "idem-admin-old", "2026-05-13T01:00:00Z");
        insertItem("ord_admin_old", "LIMITED-OLD", "SKU-OLD", 1, "8000.00");
        insertOrder("ord_admin_new", "member-new", "CANCELLED", "FAILED", "FAIL_CARD", "15000.00", "idem-admin-new", "2026-05-13T03:00:00Z");
        insertItem("ord_admin_new", "LIMITED-NEW", "SKU-NEW", 3, "5000.00");

        mockMvc.perform(get("/api/admin/orders")
                .param("size", "2")
                .header("X-Correlation-Id", "corr-admin-orders"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-orders"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.page", is(0)))
            .andExpect(jsonPath("$.data.size", is(2)))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_admin_new")))
            .andExpect(jsonPath("$.data.items[0].itemCount", is(1)))
            .andExpect(jsonPath("$.data.items[0].paymentMethod", is("FAIL_CARD")))
            .andExpect(jsonPath("$.data.items[1].orderId", is("ord_query_001")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-orders")));
    }

    @Test
    void lists_recent_admin_orders_with_saga_filter() throws Exception {
        insertOrder("ord_admin_failed", "member-failed", "CANCELLED", "FAILED", "FAIL_CARD", "15000.00", "idem-admin-failed", "2026-05-13T03:00:00Z");
        insertItem("ord_admin_failed", "LIMITED-NEW", "SKU-NEW", 3, "5000.00");

        mockMvc.perform(get("/api/admin/orders")
                .param("sagaStatus", "FAILED")
                .header("X-Correlation-Id", "corr-admin-filter"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-filter"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_admin_failed")))
            .andExpect(jsonPath("$.data.items[0].sagaStatus", is("FAILED")));
    }

    @Test
    void returns_admin_order_saga_status_with_failure_detail() throws Exception {
        insertOrder("ord_admin_failed", "member-failed", "CANCELLED", "FAILED", "FAIL_CARD", "15000.00", "idem-admin-saga", "2026-05-13T03:00:00Z");
        insertOrderCancelledOutbox("ord_admin_failed", "PAYMENT_DECLINED");

        mockMvc.perform(get("/api/admin/orders/{orderId}/saga", "ord_admin_failed")
                .header("X-Correlation-Id", "corr-admin-saga"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-saga"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.orderId", is("ord_admin_failed")))
            .andExpect(jsonPath("$.data.orderStatus", is("CANCELLED")))
            .andExpect(jsonPath("$.data.sagaStatus", is("FAILED")))
            .andExpect(jsonPath("$.data.businessReason", is("PAYMENT_DECLINED")))
            .andExpect(jsonPath("$.data.lastEventType", is("OrderCancelled")))
            .andExpect(jsonPath("$.data.outboxAttempts", is(0)))
            .andExpect(jsonPath("$.data.failedAt", notNullValue()))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-saga")));
    }

    @Test
    void returns_not_found_for_unknown_admin_saga_order() throws Exception {
        mockMvc.perform(get("/api/admin/orders/{orderId}/saga", "ord_admin_missing")
                .header("X-Correlation-Id", "corr-admin-saga-missing"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-saga-missing"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_NOT_FOUND")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-saga-missing")));
    }

    private void insertOrder() {
        insertOrder(
            "ord_query_001",
            "member-query-1",
            "CONFIRMED",
            "COMPLETED",
            "CARD",
            "29000.00",
            "WELCOME10",
            "5000.00",
            "24000.00",
            "idem-query-001",
            "2026-05-13T02:00:00Z"
        );
        insertItem("ord_query_001", "LIMITED-001", "SKU-001", 2, "12000.00");
        insertItem("ord_query_001", "LIMITED-002", "SKU-002", 1, "5000.00");
    }

    private void insertOrder(
        String orderId,
        String memberId,
        String status,
        String sagaStatus,
        String paymentMethod,
        String totalAmount,
        String idempotencyKey,
        String createdAt
    ) {
        insertOrder(orderId, memberId, status, sagaStatus, paymentMethod, totalAmount, null, "0.00", totalAmount, idempotencyKey, createdAt);
    }

    private void insertOrder(
        String orderId,
        String memberId,
        String status,
        String sagaStatus,
        String paymentMethod,
        String totalAmount,
        String couponCode,
        String discountAmount,
        String payableAmount,
        String idempotencyKey,
        String createdAt
    ) {
        jdbcClient.sql("""
                insert into customer_orders (
                  order_id, member_id, status, saga_status, total_amount, coupon_code, discount_amount, payable_amount, payment_method,
                  idempotency_key, created_at, updated_at
                )
                values (
                  :orderId, :memberId, :status, :sagaStatus, :totalAmount, :couponCode, :discountAmount, :payableAmount, :paymentMethod,
                  :idempotencyKey, :createdAt::timestamptz, :createdAt::timestamptz
                )
                """)
            .param("orderId", orderId)
            .param("memberId", memberId)
            .param("status", status)
            .param("sagaStatus", sagaStatus)
            .param("totalAmount", new BigDecimal(totalAmount))
            .param("couponCode", couponCode)
            .param("discountAmount", new BigDecimal(discountAmount))
            .param("payableAmount", new BigDecimal(payableAmount))
            .param("paymentMethod", paymentMethod)
            .param("idempotencyKey", idempotencyKey)
            .param("createdAt", createdAt)
            .update();
    }

    private void insertItem(String productCode, String skuId, int quantity, String unitPrice) {
        insertItem("ord_query_001", productCode, skuId, quantity, unitPrice);
    }

    private void insertItem(String orderId, String productCode, String skuId, int quantity, String unitPrice) {
        jdbcClient.sql("""
                insert into order_items (order_id, product_code, sku_id, quantity, unit_price, created_at)
                values (:orderId, :productCode, :skuId, :quantity, :unitPrice, now())
                """)
            .param("orderId", orderId)
            .param("productCode", productCode)
            .param("skuId", skuId)
            .param("quantity", quantity)
            .param("unitPrice", new BigDecimal(unitPrice))
            .update();
    }

    private void insertOrderCancelledOutbox(String orderId, String reason) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  gen_random_uuid(), 'order', :orderId, 'OrderCancelled', 1,
                  'stockrush.order.events.v1', :orderId, 'corr-admin-saga', 'idem-admin-saga',
                  jsonb_build_object('orderId', :orderId, 'reason', :reason, 'cancelledAt', '2026-05-13T03:05:00Z'),
                  '{}'::jsonb, 'PENDING', 0, 5, '2026-05-13T03:05:00Z'::timestamptz, '2026-05-13T03:05:00Z'::timestamptz
                )
                """)
            .param("orderId", orderId)
            .param("reason", reason)
            .update();
    }
}
