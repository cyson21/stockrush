package com.stockrush.order.infra.kafka;

import com.stockrush.order.application.InventoryReservationFailedPayload;
import com.stockrush.order.application.InventoryReservedPayload;
import com.stockrush.order.application.OrderSagaEventHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class OrderInventoryEventConsumer {

    private final ObjectMapper objectMapper;
    private final OrderSagaEventHandler handler;

    OrderInventoryEventConsumer(ObjectMapper objectMapper, OrderSagaEventHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(
        topics = "stockrush.inventory.events.v1",
        groupId = "${stockrush.kafka.consumer.group-id:order-service}",
        autoStartup = "${stockrush.kafka.listeners.enabled:false}"
    )
    void consume(String message) {
        try {
            switch (eventType(message)) {
                case "InventoryReserved" -> handler.handleInventoryReserved(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<InventoryReservedPayload>>() {
                    })
                );
                case "InventoryReservationFailed" -> handler.handleInventoryReservationFailed(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<InventoryReservationFailedPayload>>() {
                    })
                );
                default -> throw new IllegalArgumentException("unsupported inventory event");
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to parse inventory event", e);
        }
    }

    private String eventType(String message) throws JacksonException {
        return objectMapper.readTree(message).required("eventType").stringValue();
    }
}
