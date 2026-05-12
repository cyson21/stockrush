package com.stockrush.payment.infra.outbox;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnMissingBean(OutboxEventPublisher.class)
class KafkaOutboxEventPublisher implements OutboxEventPublisher {

    private static final int SEND_TIMEOUT_SECONDS = 10;
    private static final String SOURCE_SERVICE = "payment-service";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    KafkaOutboxEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(OutboxRelayEvent event) {
        try {
            kafkaTemplate.send(event.topic(), event.partitionKey(), envelopeJson(event))
                .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException("outbox publish interrupted");
        } catch (Exception e) {
            throw new OutboxPublishException("outbox publish failed: " + e.getMessage());
        }
    }

    private String envelopeJson(OutboxRelayEvent event) throws JacksonException {
        JsonNode payload = objectMapper.readTree(event.payload());
        JsonNode headers = objectMapper.readTree(event.headersJson());
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", event.eventId().toString());
        envelope.put("eventType", event.eventType());
        envelope.put("eventVersion", event.eventVersion());
        envelope.put("aggregateType", event.aggregateType());
        envelope.put("aggregateId", event.aggregateId());
        envelope.put("correlationId", event.correlationId());
        putNullableText(envelope, "causationId", headers.path("causationId").stringValue(null));
        envelope.put("idempotencyKey", event.idempotencyKey());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("sourceService", headers.path("sourceService").stringValue(SOURCE_SERVICE));
        envelope.set("payload", payload);
        return objectMapper.writeValueAsString(envelope);
    }

    private void putNullableText(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
            return;
        }
        node.put(field, value);
    }
}
