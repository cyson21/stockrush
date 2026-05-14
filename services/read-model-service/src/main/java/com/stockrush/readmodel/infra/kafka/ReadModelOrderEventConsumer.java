package com.stockrush.readmodel.infra.kafka;

import com.stockrush.readmodel.application.OrderCancelledPayload;
import com.stockrush.readmodel.application.OrderConfirmedPayload;
import com.stockrush.readmodel.application.OrderCreatedPayload;
import com.stockrush.readmodel.application.OrderReadModelProjectionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "stockrush.kafka.listeners.enabled", havingValue = "true")
class ReadModelOrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final OrderReadModelProjectionHandler handler;

    ReadModelOrderEventConsumer(ObjectMapper objectMapper, OrderReadModelProjectionHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(
        topics = "stockrush.order.events.v1",
        groupId = "${spring.kafka.consumer.group-id:read-model-service}",
        autoStartup = "${stockrush.kafka.listeners.enabled:false}"
    )
    void consume(String message) {
        try {
            switch (eventType(message)) {
                case "OrderCreated" -> handler.handleOrderCreated(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<OrderCreatedPayload>>() {
                    })
                );
                case "OrderConfirmed" -> handler.handleOrderConfirmed(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<OrderConfirmedPayload>>() {
                    })
                );
                case "OrderCancelled" -> handler.handleOrderCancelled(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<OrderCancelledPayload>>() {
                    })
                );
                default -> {
                }
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to parse order event", e);
        }
    }

    private String eventType(String message) throws JacksonException {
        return objectMapper.readTree(message).required("eventType").stringValue();
    }
}
