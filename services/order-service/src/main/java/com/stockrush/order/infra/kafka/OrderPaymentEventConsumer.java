package com.stockrush.order.infra.kafka;

import com.stockrush.order.application.OrderSagaEventHandler;
import com.stockrush.order.application.PaymentAuthorizationDelayedPayload;
import com.stockrush.order.application.PaymentAuthorizationFailedPayload;
import com.stockrush.order.application.PaymentAuthorizedPayload;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class OrderPaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final OrderSagaEventHandler handler;

    OrderPaymentEventConsumer(ObjectMapper objectMapper, OrderSagaEventHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(
        topics = "stockrush.payment.events.v1",
        groupId = "${stockrush.kafka.consumer.group-id:order-service}",
        autoStartup = "${stockrush.kafka.listeners.enabled:false}"
    )
    void consume(String message) {
        try {
            switch (eventType(message)) {
                case "PaymentAuthorized" -> handler.handlePaymentAuthorized(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<PaymentAuthorizedPayload>>() {
                    })
                );
                case "PaymentAuthorizationFailed" -> handler.handlePaymentAuthorizationFailed(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<PaymentAuthorizationFailedPayload>>() {
                    })
                );
                case "PaymentAuthorizationDelayed" -> handler.handlePaymentAuthorizationDelayed(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<PaymentAuthorizationDelayedPayload>>() {
                    })
                );
                default -> throw new IllegalArgumentException("unsupported payment event");
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to parse payment event", e);
        }
    }

    private String eventType(String message) throws JacksonException {
        return objectMapper.readTree(message).required("eventType").stringValue();
    }
}
