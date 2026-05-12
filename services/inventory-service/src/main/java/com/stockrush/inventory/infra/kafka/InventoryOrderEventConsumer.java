package com.stockrush.inventory.infra.kafka;

import com.stockrush.inventory.application.InventoryReservationHandler;
import com.stockrush.inventory.application.OrderCreatedPayload;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class InventoryOrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final InventoryReservationHandler handler;

    InventoryOrderEventConsumer(ObjectMapper objectMapper, InventoryReservationHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(
        topics = "stockrush.order.events.v1",
        groupId = "${stockrush.kafka.consumer.group-id:inventory-service}",
        autoStartup = "${stockrush.kafka.listeners.enabled:false}"
    )
    void consume(String message) {
        try {
            handler.handle(objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<OrderCreatedPayload>>() {
            }));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to parse order event", e);
        }
    }
}
