package com.stockrush.inventory.application;

import com.stockrush.inventory.infra.kafka.KafkaEventEnvelope;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryReservationHandler {

    private static final String CONSUMER_GROUP = "inventory-service";
    private static final String INVENTORY_TOPIC = "stockrush.inventory.events.v1";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<UUID> idSupplier;

    public InventoryReservationHandler(
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
    public void handle(KafkaEventEnvelope<OrderCreatedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        OrderCreatedPayload payload = event.payload();
        if (!hasEnoughStock(payload.items())) {
            writeReservationFailed(event, "INSUFFICIENT_STOCK");
            return;
        }

        for (OrderCreatedItemPayload item : payload.items()) {
            reserve(event.aggregateId(), item);
        }
        writeReserved(event);
    }

    private boolean markProcessed(KafkaEventEnvelope<OrderCreatedPayload> event) {
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

    private boolean hasEnoughStock(List<OrderCreatedItemPayload> items) {
        for (OrderCreatedItemPayload item : items) {
            int availableRows = jdbcClient.sql("""
                    select count(*)
                    from stock_items
                    where sku_id = :skuId
                      and available_quantity >= :quantity
                    """)
                .param("skuId", item.skuId())
                .param("quantity", item.quantity())
                .query(Integer.class)
                .single();
            if (availableRows == 0) {
                return false;
            }
        }
        return true;
    }

    private void reserve(String orderId, OrderCreatedItemPayload item) {
        jdbcClient.sql("""
                update stock_items
                set available_quantity = available_quantity - :quantity,
                    reserved_quantity = reserved_quantity + :quantity,
                    version = version + 1,
                    updated_at = now()
                where sku_id = :skuId
                """)
            .param("quantity", item.quantity())
            .param("skuId", item.skuId())
            .update();

        jdbcClient.sql("""
                insert into stock_reservations (
                  reservation_id, order_id, sku_id, quantity, status, expires_at, created_at, updated_at
                )
                values (:reservationId, :orderId, :skuId, :quantity, 'RESERVED', now() + interval '15 minutes', now(), now())
                """)
            .param("reservationId", idSupplier.get())
            .param("orderId", orderId)
            .param("skuId", item.skuId())
            .param("quantity", item.quantity())
            .update();
    }

    private void writeReserved(KafkaEventEnvelope<OrderCreatedPayload> event) {
        OrderCreatedPayload source = event.payload();
        InventoryReservedPayload payload = new InventoryReservedPayload(
            source.orderId(),
            source.items().stream()
                .map(item -> new InventoryReservedItemPayload(item.productCode(), item.skuId(), item.quantity()))
                .toList(),
            clock.instant()
        );
        writeOutbox(event, "InventoryReserved", payload);
    }

    private void writeReservationFailed(KafkaEventEnvelope<OrderCreatedPayload> event, String reason) {
        writeOutbox(
            event,
            "InventoryReservationFailed",
            new InventoryReservationFailedPayload(event.aggregateId(), reason, clock.instant())
        );
    }

    private void writeOutbox(KafkaEventEnvelope<OrderCreatedPayload> source, String eventType, Object payload) {
        jdbcClient.sql("""
                insert into outbox_events (
                  event_id, aggregate_type, aggregate_id, event_type, event_version,
                  topic, partition_key, correlation_id, idempotency_key, payload, headers,
                  status, retry_count, max_retry_count, created_at, updated_at
                )
                values (
                  :eventId, 'inventory-reservation', :aggregateId, :eventType, 1,
                  :topic, :partitionKey, :correlationId, :idempotencyKey, :payload, '{}'::jsonb,
                  'PENDING', 0, 5, now(), now()
                )
                """)
            .param("eventId", idSupplier.get())
            .param("aggregateId", source.aggregateId())
            .param("eventType", eventType)
            .param("topic", INVENTORY_TOPIC)
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
            throw new IllegalArgumentException("failed to serialize inventory payload", e);
        }
    }

    private SqlParameterValue jsonb(String json) {
        return new SqlParameterValue(Types.OTHER, json);
    }
}
