package com.stockrush.order.infra.kafka;

import com.stockrush.order.application.InventoryReservationFailedPayload;
import com.stockrush.order.application.InventoryReservedPayload;
import com.stockrush.order.application.OrderSagaEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class OrderInventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderInventoryEventConsumer.class);

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
            String type = eventType(message);
            switch (type) {
                case "InventoryReserved" -> handler.handleInventoryReserved(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<InventoryReservedPayload>>() {
                    })
                );
                case "InventoryReservationFailed" -> handler.handleInventoryReservationFailed(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<InventoryReservationFailedPayload>>() {
                    })
                );
                default -> log.debug("Ignoring inventory event type not handled by order saga: {}", type);
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to parse inventory event", e);
        }
    }

    private String eventType(String message) throws JacksonException {
        return objectMapper.readTree(message).required("eventType").stringValue();
    }
}
