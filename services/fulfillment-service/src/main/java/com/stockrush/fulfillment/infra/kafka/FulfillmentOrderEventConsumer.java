package com.stockrush.fulfillment.infra.kafka;

import com.stockrush.fulfillment.application.FulfillmentOrderEventHandler;
import com.stockrush.fulfillment.application.OrderConfirmedPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "stockrush.kafka.listeners.enabled", havingValue = "true")
class FulfillmentOrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final FulfillmentOrderEventHandler handler;

    FulfillmentOrderEventConsumer(ObjectMapper objectMapper, FulfillmentOrderEventHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(
        topics = "stockrush.order.events.v1",
        groupId = "${spring.kafka.consumer.group-id:fulfillment-service}",
        autoStartup = "${stockrush.kafka.listeners.enabled:false}"
    )
    void consume(String message) {
        try {
            if ("OrderConfirmed".equals(eventType(message))) {
                handler.handleOrderConfirmed(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<OrderConfirmedPayload>>() {
                    })
                );
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to parse order event", e);
        }
    }

    private String eventType(String message) throws JacksonException {
        return objectMapper.readTree(message).required("eventType").stringValue();
    }
}
