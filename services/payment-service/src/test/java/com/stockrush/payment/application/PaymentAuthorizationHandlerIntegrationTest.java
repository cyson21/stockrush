package com.stockrush.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.payment.infra.kafka.KafkaEventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=payment",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class PaymentAuthorizationHandlerIntegrationTest {

    private static final UUID COMMAND_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c01");

    @Autowired
    private PaymentAuthorizationHandler handler;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from payments").update();
    }

    @Test
    void authorizes_payment_and_writes_outbox_when_command_received() {
        handler.handle(paymentAuthorizationRequested());

        assertEquals(1, queryInt("select count(*) from payments where order_id = 'ord_payment_001'"));
        assertEquals("AUTHORIZED", queryString("select status from payments where order_id = 'ord_payment_001'"));
        assertEquals("CARD", queryString("select method from payments where order_id = 'ord_payment_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + COMMAND_EVENT_ID + "'"));
        assertEquals("PaymentAuthorized", queryString("select event_type from outbox_events"));
        assertEquals("PENDING", queryString("select status from outbox_events"));
    }

    private KafkaEventEnvelope<PaymentAuthorizationRequestedPayload> paymentAuthorizationRequested() {
        return new KafkaEventEnvelope<>(
            COMMAND_EVENT_ID,
            "PaymentAuthorizationRequested",
            1,
            "order",
            "ord_payment_001",
            "corr-payment-001",
            null,
            "idem-payment-001",
            Instant.parse("2026-05-12T16:00:00Z"),
            "order-service",
            new PaymentAuthorizationRequestedPayload(
                "ord_payment_001",
                new BigDecimal("24000.00"),
                "CARD"
            )
        );
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
