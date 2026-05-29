// CreateOrderControllerIntegrationTest: API 진입점으로 요청/응답 경계와 HTTP 흐름을 정리합니다.

package com.stockrush.order.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockrush.order.application.CouponQuoteClient;
import com.stockrush.order.application.CouponQuoteResult;
import com.stockrush.order.application.CouponQuoteUnavailableException;
import com.stockrush.order.application.OrderIdGenerator;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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
class CreateOrderControllerIntegrationTest {

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
    }

    @Test
    void creates_order_and_returns_common_response() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-001")
                .header("X-Correlation-Id", "corr-http-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-1",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 2,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-http-001"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.orderId", is("ord_http_001")))
            .andExpect(jsonPath("$.data.status", is("CREATED")))
            .andExpect(jsonPath("$.data.sagaStatus", is("STARTED")))
            .andExpect(jsonPath("$.data.paymentMethod", is("CARD")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-001")));
    }

    @Test
    void creates_order_with_authenticated_subject_instead_of_body_member_id() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-auth-subject")
                .header("X-Correlation-Id", "corr-http-auth-subject")
                .header("X-StockRush-Subject", "member-authenticated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-spoofed",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 1,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-http-auth-subject"))
            .andExpect(jsonPath("$.success", is(true)));

        org.junit.jupiter.api.Assertions.assertEquals(
            "member-authenticated",
            jdbcClient.sql("select member_id from customer_orders where order_id = 'ord_http_001'")
                .query(String.class)
                .single()
        );
    }

    @Test
    void replays_same_idempotency_key_with_existing_order_response() throws Exception {
        String requestBody = """
            {
              "memberId": "member-1",
              "items": [
                {
                  "productCode": "LIMITED-001",
                  "skuId": "SKU-001",
                  "quantity": 2,
                  "unitPrice": 12000.00
                }
              ]
            }
            """;

        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-replay")
                .header("X-Correlation-Id", "corr-http-replay-first")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.orderId", is("ord_http_001")));

        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-replay")
                .header("X-Correlation-Id", "corr-http-replay-second")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-http-replay-second"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.orderId", is("ord_http_001")))
            .andExpect(jsonPath("$.data.status", is("CREATED")))
            .andExpect(jsonPath("$.data.sagaStatus", is("STARTED")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-replay-second")));

        org.junit.jupiter.api.Assertions.assertEquals(1, count("customer_orders"));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("order_items"));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("outbox_events"));
    }

    @Test
    void rejects_same_idempotency_key_replay_for_different_authenticated_subject() throws Exception {
        String requestBody = """
            {
              "memberId": "ignored-member",
              "items": [
                {
                  "productCode": "LIMITED-001",
                  "skuId": "SKU-001",
                  "quantity": 1,
                  "unitPrice": 12000.00
                }
              ]
            }
            """;

        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-auth-replay")
                .header("X-Correlation-Id", "corr-http-auth-replay-first")
                .header("X-StockRush-Subject", "member-authenticated-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.orderId", is("ord_http_001")));

        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-auth-replay")
                .header("X-Correlation-Id", "corr-http-auth-replay-second")
                .header("X-StockRush-Subject", "member-authenticated-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isForbidden())
            .andExpect(header().string("X-Correlation-Id", "corr-http-auth-replay-second"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_FORBIDDEN")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-auth-replay-second")));

        org.junit.jupiter.api.Assertions.assertEquals(1, count("customer_orders"));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("order_items"));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("outbox_events"));
    }

    @Test
    void creates_order_with_payment_method() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-payment-method")
                .header("X-Correlation-Id", "corr-http-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-1",
                      "paymentMethod": "FAIL_CARD",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 2,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-http-payment-method"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.paymentMethod", is("FAIL_CARD")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-payment-method")));

        assertPaymentMethod("FAIL_CARD");
    }

    @Test
    void creates_order_with_coupon_pricing() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-coupon")
                .header("X-Correlation-Id", "corr-http-coupon")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-1",
                      "paymentMethod": "CARD",
                      "couponCode": "WELCOME10",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 2,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-http-coupon"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.couponCode", is("WELCOME10")))
            .andExpect(jsonPath("$.data.totalAmount", is(24000.00)))
            .andExpect(jsonPath("$.data.discountAmount", is(3000.00)))
            .andExpect(jsonPath("$.data.payableAmount", is(21000.00)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-coupon")));

        assertCouponPricing("WELCOME10", "3000.00", "21000.00");
    }

    @Test
    void rejects_order_when_coupon_is_not_applicable() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-coupon-invalid")
                .header("X-Correlation-Id", "corr-http-coupon-invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-1",
                      "paymentMethod": "CARD",
                      "couponCode": "EXPIRED10",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 2,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-http-coupon-invalid"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_COUPON_NOT_APPLICABLE")))
            .andExpect(jsonPath("$.error.message", is("Coupon could not be applied: COUPON_OUT_OF_PERIOD")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-coupon-invalid")));
    }

    @Test
    void returns_bad_gateway_when_coupon_quote_is_unavailable() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-coupon-unavailable")
                .header("X-Correlation-Id", "corr-http-coupon-unavailable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-1",
                      "paymentMethod": "CARD",
                      "couponCode": "SLOW10",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 2,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isBadGateway())
            .andExpect(header().string("X-Correlation-Id", "corr-http-coupon-unavailable"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_COUPON_QUOTE_UNAVAILABLE")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-coupon-unavailable")));
    }

    @Test
    void creates_order_with_default_payment_method_when_omitted() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-default-payment")
                .header("X-Correlation-Id", "corr-http-default-payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-1",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 1,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-http-default-payment"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.paymentMethod", is("CARD")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-default-payment")));

        assertPaymentMethod("CARD");
    }

    @Test
    void rejects_request_without_idempotency_key() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("X-Correlation-Id", "corr-http-missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-1",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 2,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-http-missing"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("COMMON_MISSING_IDEMPOTENCY_KEY")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-missing")));
    }

    @Test
    void rejects_invalid_order_item_quantity() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", "idem-http-invalid")
                .header("X-Correlation-Id", "corr-http-invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "member-1",
                      "items": [
                        {
                          "productCode": "LIMITED-001",
                          "skuId": "SKU-001",
                          "quantity": 0,
                          "unitPrice": 12000.00
                        }
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-http-invalid"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("ORDER_INVALID_REQUEST")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-http-invalid")));
    }

    @TestConfiguration
    static class FixedIdsConfig {

        @Bean
        @Primary
        OrderIdGenerator fixedOrderIdGenerator() {
            return () -> "ord_http_001";
        }

        @Bean
        @Primary
        Supplier<UUID> fixedEventIdSupplier() {
            return () -> UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7a22");
        }

        @Bean
        @Primary
        CouponQuoteClient fixedCouponQuoteClient() {
            return (couponCode, orderAmount, correlationId) -> {
                if ("EXPIRED10".equals(couponCode)) {
                    return new CouponQuoteResult(couponCode, false, BigDecimal.ZERO, orderAmount, "COUPON_OUT_OF_PERIOD");
                }
                if ("SLOW10".equals(couponCode)) {
                    throw new CouponQuoteUnavailableException("Coupon quote request timed out.");
                }
                return new CouponQuoteResult(
                    couponCode,
                    true,
                    new BigDecimal("3000.00"),
                    orderAmount.subtract(new BigDecimal("3000.00")),
                    "APPLIED"
                );
            };
        }
    }

    private void assertPaymentMethod(String expected) {
        String paymentMethod = jdbcClient.sql("select payment_method from customer_orders where order_id = 'ord_http_001'")
            .query(String.class)
            .single();
        org.junit.jupiter.api.Assertions.assertEquals(expected, paymentMethod);
    }

    private void assertCouponPricing(String couponCode, String discountAmount, String payableAmount) {
        String actualCouponCode = jdbcClient.sql("select coupon_code from customer_orders where order_id = 'ord_http_001'")
            .query(String.class)
            .single();
        BigDecimal actualDiscountAmount = jdbcClient.sql("select discount_amount from customer_orders where order_id = 'ord_http_001'")
            .query(BigDecimal.class)
            .single();
        BigDecimal actualPayableAmount = jdbcClient.sql("select payable_amount from customer_orders where order_id = 'ord_http_001'")
            .query(BigDecimal.class)
            .single();

        org.junit.jupiter.api.Assertions.assertEquals(couponCode, actualCouponCode);
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal(discountAmount), actualDiscountAmount);
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal(payableAmount), actualPayableAmount);
    }

    private int count(String tableName) {
        return jdbcClient.sql("select count(*) from " + tableName).query(Integer.class).single();
    }
}
