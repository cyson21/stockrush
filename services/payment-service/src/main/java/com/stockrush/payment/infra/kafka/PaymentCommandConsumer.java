package com.stockrush.payment.infra.kafka;

import com.stockrush.payment.application.PaymentAuthorizationHandler;
import com.stockrush.payment.application.PaymentAuthorizationRequestedPayload;
import com.stockrush.payment.application.PaymentCancelRequestedPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "stockrush.kafka.listeners.enabled", havingValue = "true")
class PaymentCommandConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentAuthorizationHandler handler;

    PaymentCommandConsumer(ObjectMapper objectMapper, PaymentAuthorizationHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(
        topics = "stockrush.payment.commands.v1",
        groupId = "${stockrush.kafka.consumer.group-id:payment-service}",
        autoStartup = "${stockrush.kafka.listeners.enabled:false}"
    )
    void consume(String message) {
        try {
            switch (eventType(message)) {
                case "PaymentAuthorizationRequested" -> handler.handle(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<PaymentAuthorizationRequestedPayload>>() {
                    })
                );
                case "PaymentCancelRequested" -> handler.handleCancel(
                    objectMapper.readValue(message, new TypeReference<KafkaEventEnvelope<PaymentCancelRequestedPayload>>() {
                    })
                );
                default -> throw new IllegalArgumentException("unsupported payment command");
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("failed to parse payment command", e);
        }
    }

    private String eventType(String message) throws JacksonException {
        return objectMapper.readTree(message).required("eventType").stringValue();
    }
}
