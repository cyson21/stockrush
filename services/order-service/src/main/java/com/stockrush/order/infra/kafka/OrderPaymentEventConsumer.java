package com.stockrush.order.infra.kafka;

import com.stockrush.order.application.OrderSagaEventHandler;
import com.stockrush.order.application.PaymentAuthorizationDelayedPayload;
import com.stockrush.order.application.PaymentAuthorizationFailedPayload;
import com.stockrush.order.application.PaymentAuthorizedPayload;
import com.stockrush.order.application.PaymentCanceledPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class OrderPaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentEventConsumer.class);

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
            String type = eventType(message);
            switch (type) {
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
                case "PaymentCanceled" -> handler.handlePaymentCanceled(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<PaymentCanceledPayload>>() {
                    })
                );
                default -> log.debug("Ignoring payment event type not handled by order saga: {}", type);
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to parse payment event", e);
        }
    }

    private String eventType(String message) throws JacksonException {
        return objectMapper.readTree(message).required("eventType").stringValue();
    }
}
