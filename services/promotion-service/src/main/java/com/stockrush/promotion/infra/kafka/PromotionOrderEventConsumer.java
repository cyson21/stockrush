package com.stockrush.promotion.infra.kafka;

import com.stockrush.promotion.application.OrderCancelledPayload;
import com.stockrush.promotion.application.OrderConfirmedPayload;
import com.stockrush.promotion.application.OrderCreatedPayload;
import com.stockrush.promotion.application.PromotionCouponUsageEventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "stockrush.kafka.listeners.enabled", havingValue = "true")
class PromotionOrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final PromotionCouponUsageEventHandler handler;

    PromotionOrderEventConsumer(ObjectMapper objectMapper, PromotionCouponUsageEventHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(
        topics = "stockrush.order.events.v1",
        groupId = "${spring.kafka.consumer.group-id:promotion-service}",
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
