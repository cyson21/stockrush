package com.stockrush.fulfillment.application;

import com.stockrush.fulfillment.infra.kafka.KafkaEventEnvelope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentOrderEventHandler {

    private static final String CONSUMER_GROUP = "fulfillment-service";

    private final JdbcClient jdbcClient;

    public FulfillmentOrderEventHandler(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void handleOrderConfirmed(KafkaEventEnvelope<OrderConfirmedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        OrderConfirmedPayload payload = event.payload();
        validate(payload);

        jdbcClient.sql("""
                insert into fulfillment_requests (
                  request_id, order_id, status, requested_at, source_event_id,
                  correlation_id, idempotency_key, created_at, updated_at
                )
                values (
                  :requestId, :orderId, 'PREPARING', :requestedAt, :sourceEventId,
                  :correlationId, :idempotencyKey, now(), now()
                )
                on conflict (order_id) do nothing
                """)
            .param("requestId", UUID.randomUUID())
            .param("orderId", payload.orderId())
            .param("requestedAt", toOffsetDateTime(payload.confirmedAt()))
            .param("sourceEventId", event.eventId())
            .param("correlationId", event.correlationId())
            .param("idempotencyKey", event.idempotencyKey())
            .update();
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

    private void validate(OrderConfirmedPayload payload) {
        if (payload.orderId() == null || payload.orderId().isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (payload.confirmedAt() == null) {
            throw new IllegalArgumentException("confirmedAt must not be null");
        }
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
