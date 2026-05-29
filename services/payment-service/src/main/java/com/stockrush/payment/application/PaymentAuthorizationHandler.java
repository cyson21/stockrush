package com.stockrush.payment.application;

import com.stockrush.payment.infra.kafka.KafkaEventEnvelope;
import java.sql.Types;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
/**
 * 외부 이벤트/명령을 수신해 유효성, 멱등성 체크, 다음 상태 전환을 조합해 처리하는 핸들러입니다.
 */


@Service
public class PaymentAuthorizationHandler {

    private static final String CONSUMER_GROUP = "payment-service";
    private static final String PAYMENT_TOPIC = "stockrush.payment.events.v1";
    private static final String FAIL_CARD_METHOD = "FAIL_CARD";
    private static final String DELAY_CARD_METHOD = "DELAY_CARD";
    private static final String PAYMENT_DECLINED_REASON = "PAYMENT_DECLINED";
    private static final String PAYMENT_DELAYED_REASON = "PAYMENT_DELAYED";
    private static final String PAYMENT_CANCELED_REASON = "PAYMENT_CANCELED";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<UUID> idSupplier;

    public PaymentAuthorizationHandler(
        JdbcClient jdbcClient,
        ObjectMapper objectMapper,
        Clock clock,
        Supplier<UUID> idSupplier
    ) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.idSupplier = idSupplier;
    }

    @Transactional
    public void handle(KafkaEventEnvelope<PaymentAuthorizationRequestedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        PaymentAuthorizationRequestedPayload payload = event.payload();
        UUID paymentId = idSupplier.get();
        boolean failedAuthorization = FAIL_CARD_METHOD.equals(payload.method());
        boolean delayedAuthorization = DELAY_CARD_METHOD.equals(payload.method());
        jdbcClient.sql("""
                insert into payments (
                  payment_id, order_id, amount, method, status, failure_reason,
                  idempotency_key, created_at, updated_at
                )
                values (
                  :paymentId, :orderId, :amount, :method, :status, :failureReason,
                  :idempotencyKey, now(), now()
                )
                """)
            .param("paymentId", paymentId)
            .param("orderId", payload.orderId())
            .param("amount", payload.amount())
            .param("method", payload.method())
            .param("status", paymentStatus(failedAuthorization, delayedAuthorization))
            .param("failureReason", paymentReason(failedAuthorization, delayedAuthorization))
            .param("idempotencyKey", event.idempotencyKey())
            .update();

        if (failedAuthorization) {
            writeOutbox(
                event,
                "PaymentAuthorizationFailed",
                new PaymentAuthorizationFailedPayload(
                    payload.orderId(),
                    payload.amount(),
                    payload.method(),
                    PAYMENT_DECLINED_REASON,
                    clock.instant()
                )
            );
            return;
        }

        if (delayedAuthorization) {
            writeOutbox(
                event,
                "PaymentAuthorizationDelayed",
                new PaymentAuthorizationDelayedPayload(
                    payload.orderId(),
                    payload.amount(),
                    payload.method(),
                    PAYMENT_DELAYED_REASON,
                    clock.instant()
                )
            );
            return;
        }

        writeOutbox(
            event,
            "PaymentAuthorized",
            new PaymentAuthorizedPayload(paymentId, payload.orderId(), payload.amount(), payload.method(), clock.instant())
        );
    }

    @Transactional
    public void handleCancel(KafkaEventEnvelope<PaymentCancelRequestedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        PaymentToCancel payment = findPaymentToCancel(event.payload().orderId());
        if ("CANCELED".equals(payment.status())) {
            return;
        }
        if (!"DELAYED".equals(payment.status())) {
            throw new IllegalArgumentException("Only delayed payments can be canceled.");
        }

        jdbcClient.sql("""
                update payments
                set status = 'CANCELED',
                    failure_reason = :reason,
                    updated_at = now()
                where order_id = :orderId
                """)
            .param("reason", PAYMENT_CANCELED_REASON)
            .param("orderId", event.payload().orderId())
            .update();

        writeOutbox(
            event,
            "PaymentCanceled",
            new PaymentCanceledPayload(
                event.payload().orderId(),
                payment.amount(),
                payment.method(),
                PAYMENT_CANCELED_REASON,
                clock.instant()
            )
        );
    }

    private String paymentStatus(boolean failedAuthorization, boolean delayedAuthorization) {
        if (failedAuthorization) {
            return "FAILED";
        }
        if (delayedAuthorization) {
            return "DELAYED";
        }
        return "AUTHORIZED";
    }

    private String paymentReason(boolean failedAuthorization, boolean delayedAuthorization) {
        if (failedAuthorization) {
            return PAYMENT_DECLINED_REASON;
        }
        if (delayedAuthorization) {
            return PAYMENT_DELAYED_REASON;
        }
        return null;
    }

    private boolean markProcessed(KafkaEventEnvelope<?> event) {
        int inserted = jdbcClient.sql("""
                insert into processed_events (
                  event_id, consumer_group, event_type, aggregate_id, idempotency_key, processed_at
                )
                values (:eventId, :consumerGroup, :eventType, :aggregateId, :idempotencyKey, now())
                on conflict do nothing
                """)
            .param("eventId", event.eventId())
            .param("consumerGroup", CONSUMER_GROUP)
            .param("eventType", event.eventType())
            .param("aggregateId", event.aggregateId())
            .param("idempotencyKey", event.idempotencyKey())
            .update();

        return inserted == 1;
    }

    private PaymentToCancel findPaymentToCancel(String orderId) {
        return jdbcClient.sql("""
                select amount, method, status
                from payments
                where order_id = :orderId
                for update
                """)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new PaymentToCancel(
                rs.getBigDecimal("amount"),
                rs.getString("method"),
                rs.getString("status")
            ))
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("payment not found: " + orderId));
    }

    private void writeOutbox(
        KafkaEventEnvelope<?> source,
        String eventType,
        Object payload
    ) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  :eventId, 'payment', :aggregateId, :eventType, 1,
                  :topic, :partitionKey, :correlationId, :idempotencyKey, :payload, '{}'::jsonb,
                  'PENDING', 0, 5, now(), now()
                )
                """)
            .param("eventId", idSupplier.get())
            .param("aggregateId", source.aggregateId())
            .param("eventType", eventType)
            .param("topic", PAYMENT_TOPIC)
            .param("partitionKey", source.aggregateId())
            .param("correlationId", source.correlationId())
            .param("idempotencyKey", source.idempotencyKey())
            .param("payload", jsonb(writePayload(payload)))
            .update();
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to serialize payment payload", e);
        }
    }

    private SqlParameterValue jsonb(String json) {
        return new SqlParameterValue(Types.OTHER, json);
    }

    private record PaymentToCancel(java.math.BigDecimal amount, String method, String status) {
    }
}
