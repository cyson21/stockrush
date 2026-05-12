package com.stockrush.order.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockrush.order.application.OrderIdGenerator;
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
    }

    private void assertPaymentMethod(String expected) {
        String paymentMethod = jdbcClient.sql("select payment_method from customer_orders where order_id = 'ord_http_001'")
            .query(String.class)
            .single();
        org.junit.jupiter.api.Assertions.assertEquals(expected, paymentMethod);
    }
}
