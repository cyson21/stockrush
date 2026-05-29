-- 도메인 초기화/보조 스키마 마이그레이션입니다.

package com.stockrush.promotion.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.promotion.application.PromotionCouponUsageEventHandler;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=promotion",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush",
    "stockrush.kafka.listeners.enabled=false",
    "spring.kafka.listener.auto-startup=false"
})
class PromotionOrderEventConsumerIntegrationTest {

    private PromotionOrderEventConsumer consumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PromotionCouponUsageEventHandler handler;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        consumer = new PromotionOrderEventConsumer(objectMapper, handler);
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from coupon_usages").update();
        jdbcClient.sql("delete from admin_coupon_command_idempotency").update();
        jdbcClient.sql("delete from coupons").update();
        insertCoupon("WELCOME10");
    }

    @Test
    void dispatches_order_created_and_cancelled_json_to_coupon_usage_handler() {
        consumer.consume(orderCreatedJson());
        consumer.consume(orderCancelledJson());

        assertEquals("RELEASED", queryString("select status from coupon_usages where order_id = 'ord_promotion_consumer_001'"));
        assertEquals(
            "PAYMENT_DECLINED",
            queryString("select release_reason from coupon_usages where order_id = 'ord_promotion_consumer_001'")
        );
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    private String orderCreatedJson() {
        return """
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d01",
              "eventType": "OrderCreated",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "ord_promotion_consumer_001",
              "correlationId": "corr-promotion-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-promotion-consumer-001",
              "occurredAt": "2026-05-13T04:30:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "ord_promotion_consumer_001",
                "memberId": "member-1",
                "items": [
                  {
                    "productCode": "LIMITED-001",
                    "skuId": "SKU-001",
                    "quantity": 1,
                    "unitPrice": 80000.00
                  }
                ],
                "totalAmount": 80000.00,
                "couponCode": "WELCOME10",
                "discountAmount": 5000.00,
                "payableAmount": 75000.00,
                "createdAt": "2026-05-13T04:30:00Z"
              }
            }
            """;
    }

    private String orderCancelledJson() {
        return """
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d02",
              "eventType": "OrderCancelled",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "ord_promotion_consumer_001",
              "correlationId": "corr-promotion-consumer-001",
              "causationId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d01",
              "idempotencyKey": "idem-promotion-consumer-001",
              "occurredAt": "2026-05-13T04:32:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "ord_promotion_consumer_001",
                "reason": "PAYMENT_DECLINED",
                "cancelledAt": "2026-05-13T04:32:00Z"
              }
            }
            """;
    }

    private void insertCoupon(String couponCode) {
        jdbcClient.sql("""
                insert into coupons (
                  coupon_code, name, discount_type, discount_value, min_order_amount,
                  max_discount_amount, status, starts_at, ends_at, created_at, updated_at
                )
                values (
                  :couponCode, 'Welcome 10%', 'PERCENTAGE', 10.00, 20000.00,
                  5000.00, 'ACTIVE', :startsAt, :endsAt, now(), now()
                )
                """)
            .param("couponCode", couponCode)
            .param("startsAt", OffsetDateTime.parse("2026-01-01T00:00:00Z"))
            .param("endsAt", OffsetDateTime.parse("2099-12-31T23:59:59Z"))
            .update();
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
