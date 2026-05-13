package com.stockrush.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.payment.infra.kafka.KafkaEventEnvelope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
    private static final UUID FAIL_COMMAND_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c02");
    private static final UUID DELAY_COMMAND_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c03");
    private static final UUID CANCEL_COMMAND_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c04");
    private static final UUID CANCEL_REPLAY_COMMAND_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c05");
    private static final Instant FIXED_NOW = Instant.parse("2026-05-12T17:00:00Z");

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

    @Test
    void fails_payment_and_writes_failure_outbox_for_fail_card_method() {
        handler.handle(failedPaymentAuthorizationRequested());

        assertEquals(1, queryInt("select count(*) from payments where order_id = 'ord_payment_fail_001'"));
        assertEquals("FAILED", queryString("select status from payments where order_id = 'ord_payment_fail_001'"));
        assertEquals("PAYMENT_DECLINED", queryString("select failure_reason from payments where order_id = 'ord_payment_fail_001'"));
        assertEquals("FAIL_CARD", queryString("select method from payments where order_id = 'ord_payment_fail_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + FAIL_COMMAND_EVENT_ID + "'"));
        assertEquals("PaymentAuthorizationFailed", queryString("select event_type from outbox_events"));
        assertEquals("PENDING", queryString("select status from outbox_events"));
        assertEquals("ord_payment_fail_001", queryString("select payload ->> 'orderId' from outbox_events"));
        assertEquals("24000.00", queryString("select payload ->> 'amount' from outbox_events"));
        assertEquals("FAIL_CARD", queryString("select payload ->> 'method' from outbox_events"));
        assertEquals("PAYMENT_DECLINED", queryString("select payload ->> 'reason' from outbox_events"));
        assertEquals(FIXED_NOW.toString(), queryString("select payload ->> 'failedAt' from outbox_events"));
    }

    @Test
    void delays_payment_and_writes_delayed_outbox_for_delay_card_method() {
        handler.handle(delayedPaymentAuthorizationRequested());

        assertEquals(1, queryInt("select count(*) from payments where order_id = 'ord_payment_delay_001'"));
        assertEquals("DELAYED", queryString("select status from payments where order_id = 'ord_payment_delay_001'"));
        assertEquals("PAYMENT_DELAYED", queryString("select failure_reason from payments where order_id = 'ord_payment_delay_001'"));
        assertEquals("DELAY_CARD", queryString("select method from payments where order_id = 'ord_payment_delay_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + DELAY_COMMAND_EVENT_ID + "'"));
        assertEquals("PaymentAuthorizationDelayed", queryString("select event_type from outbox_events"));
        assertEquals("PENDING", queryString("select status from outbox_events"));
        assertEquals("ord_payment_delay_001", queryString("select payload ->> 'orderId' from outbox_events"));
        assertEquals("24000.00", queryString("select payload ->> 'amount' from outbox_events"));
        assertEquals("DELAY_CARD", queryString("select payload ->> 'method' from outbox_events"));
        assertEquals("PAYMENT_DELAYED", queryString("select payload ->> 'reason' from outbox_events"));
        assertEquals(FIXED_NOW.toString(), queryString("select payload ->> 'delayedAt' from outbox_events"));
    }

    @Test
    void cancels_delayed_payment_and_writes_canceled_outbox_when_cancel_command_received() {
        insertDelayedPayment();

        handler.handleCancel(paymentCancelRequested());

        assertEquals(1, queryInt("select count(*) from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals("CANCELED", queryString("select status from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals("PAYMENT_CANCELED", queryString("select failure_reason from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals("DELAY_CARD", queryString("select method from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + CANCEL_COMMAND_EVENT_ID + "'"));
        assertEquals("PaymentCanceled", queryString("select event_type from outbox_events"));
        assertEquals("PENDING", queryString("select status from outbox_events"));
        assertEquals("ord_payment_cancel_001", queryString("select payload ->> 'orderId' from outbox_events"));
        assertEquals("24000.00", queryString("select payload ->> 'amount' from outbox_events"));
        assertEquals("DELAY_CARD", queryString("select payload ->> 'method' from outbox_events"));
        assertEquals("PAYMENT_CANCELED", queryString("select payload ->> 'reason' from outbox_events"));
        assertEquals(FIXED_NOW.toString(), queryString("select payload ->> 'canceledAt' from outbox_events"));
    }

    @Test
    void ignores_cancel_command_when_payment_is_already_canceled() {
        insertCanceledPayment();

        handler.handleCancel(paymentCancelRequested());

        assertEquals(1, queryInt("select count(*) from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals("CANCELED", queryString("select status from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals("PAYMENT_CANCELED", queryString("select failure_reason from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + CANCEL_COMMAND_EVENT_ID + "'"));
        assertEquals(0, queryInt("select count(*) from outbox_events"));
    }

    @Test
    void treats_replayed_cancel_command_for_same_order_as_noop_after_first_cancel() {
        insertDelayedPayment();

        handler.handleCancel(paymentCancelRequested());
        handler.handleCancel(paymentCancelRequested(CANCEL_REPLAY_COMMAND_EVENT_ID));

        assertEquals("CANCELED", queryString("select status from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals("PAYMENT_CANCELED", queryString("select failure_reason from payments where order_id = 'ord_payment_cancel_001'"));
        assertEquals(2, queryInt("select count(*) from processed_events where aggregate_id = 'ord_payment_cancel_001'"));
        assertEquals(1, queryInt("select count(*) from outbox_events where event_type = 'PaymentCanceled'"));
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

    private KafkaEventEnvelope<PaymentAuthorizationRequestedPayload> failedPaymentAuthorizationRequested() {
        return new KafkaEventEnvelope<>(
            FAIL_COMMAND_EVENT_ID,
            "PaymentAuthorizationRequested",
            1,
            "order",
            "ord_payment_fail_001",
            "corr-payment-fail-001",
            null,
            "idem-payment-fail-001",
            Instant.parse("2026-05-12T16:00:00Z"),
            "order-service",
            new PaymentAuthorizationRequestedPayload(
                "ord_payment_fail_001",
                new BigDecimal("24000.00"),
                "FAIL_CARD"
            )
        );
    }

    private KafkaEventEnvelope<PaymentAuthorizationRequestedPayload> delayedPaymentAuthorizationRequested() {
        return new KafkaEventEnvelope<>(
            DELAY_COMMAND_EVENT_ID,
            "PaymentAuthorizationRequested",
            1,
            "order",
            "ord_payment_delay_001",
            "corr-payment-delay-001",
            null,
            "idem-payment-delay-001",
            Instant.parse("2026-05-12T16:00:00Z"),
            "order-service",
            new PaymentAuthorizationRequestedPayload(
                "ord_payment_delay_001",
                new BigDecimal("24000.00"),
                "DELAY_CARD"
            )
        );
    }

    private KafkaEventEnvelope<PaymentCancelRequestedPayload> paymentCancelRequested() {
        return paymentCancelRequested(CANCEL_COMMAND_EVENT_ID);
    }

    private KafkaEventEnvelope<PaymentCancelRequestedPayload> paymentCancelRequested(UUID eventId) {
        return new KafkaEventEnvelope<>(
            eventId,
            "PaymentCancelRequested",
            1,
            "order",
            "ord_payment_cancel_001",
            "corr-payment-cancel-001",
            DELAY_COMMAND_EVENT_ID,
            "idem-payment-cancel-001",
            Instant.parse("2026-05-12T16:02:00Z"),
            "order-service",
            new PaymentCancelRequestedPayload(
                "ord_payment_cancel_001",
                "ADMIN_CANCEL_REQUESTED",
                Instant.parse("2026-05-12T16:02:00Z")
            )
        );
    }

    private void insertDelayedPayment() {
        jdbcClient.sql("""
                insert into payments (
                  payment_id, order_id, amount, method, status, failure_reason,
                  idempotency_key, created_at, updated_at
                )
                values (
                  gen_random_uuid(), 'ord_payment_cancel_001', 24000.00, 'DELAY_CARD', 'DELAYED', 'PAYMENT_DELAYED',
                  'idem-payment-delay-before-cancel-001', now(), now()
                )
                """)
            .update();
    }

    private void insertCanceledPayment() {
        jdbcClient.sql("""
                insert into payments (
                  payment_id, order_id, amount, method, status, failure_reason,
                  idempotency_key, created_at, updated_at
                )
                values (
                  gen_random_uuid(), 'ord_payment_cancel_001', 24000.00, 'DELAY_CARD', 'CANCELED', 'PAYMENT_CANCELED',
                  'idem-payment-already-canceled-001', now(), now()
                )
                """)
            .update();
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
