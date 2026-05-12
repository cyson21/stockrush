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

@Service
public class PaymentAuthorizationHandler {

    private static final String CONSUMER_GROUP = "payment-service";
    private static final String PAYMENT_TOPIC = "stockrush.payment.events.v1";

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
        jdbcClient.sql("""
                insert into payments (
                  payment_id, order_id, amount, method, status, failure_reason,
                  idempotency_key, created_at, updated_at
                )
                values (
                  :paymentId, :orderId, :amount, :method, 'AUTHORIZED', null,
                  :idempotencyKey, now(), now()
                )
                """)
            .param("paymentId", paymentId)
            .param("orderId", payload.orderId())
            .param("amount", payload.amount())
            .param("method", payload.method())
            .param("idempotencyKey", event.idempotencyKey())
            .update();

        writeOutbox(
            event,
            "PaymentAuthorized",
            new PaymentAuthorizedPayload(paymentId, payload.orderId(), payload.amount(), payload.method(), clock.instant())
        );
    }

    private boolean markProcessed(KafkaEventEnvelope<PaymentAuthorizationRequestedPayload> event) {
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

    private void writeOutbox(
        KafkaEventEnvelope<PaymentAuthorizationRequestedPayload> source,
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
}
