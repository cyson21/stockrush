package com.stockrush.payment.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockrush.payment.infra.outbox.OutboxRelayResult;
import com.stockrush.payment.infra.outbox.OutboxRelayService;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=payment",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush",
    "spring.kafka.bootstrap-servers=localhost:19092",
    "spring.kafka.consumer.auto-offset-reset=latest",
    "stockrush.kafka.consumer.group-id=payment-smoke-listener-${random.uuid}",
    "stockrush.kafka.listeners.enabled=true"
})
class PaymentKafkaSmokeIntegrationTest {

    private static final String PAYMENT_COMMANDS_TOPIC = "stockrush.payment.commands.v1";
    private static final String PAYMENT_EVENTS_TOPIC = "stockrush.payment.events.v1";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaListenerEndpointRegistry listenerEndpointRegistry;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from payments").update();
    }

    @Test
    void consumes_payment_authorization_requested_and_relay_publishes_payment_authorized() throws Exception {
        waitForListenerAssignment();
        String orderId = "ord_payment_kafka_smoke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID commandEventId = UUID.randomUUID();

        try (KafkaConsumer<String, String> consumer = paymentEventsConsumer()) {
            kafkaTemplate.send(PAYMENT_COMMANDS_TOPIC, orderId, paymentAuthorizationRequestedJson(commandEventId, orderId))
                .get(10, TimeUnit.SECONDS);

            await(() -> countOutbox(orderId, "PaymentAuthorized") == 1, "payment outbox event was not created");

            assertEquals(1, countPayments(orderId));
            assertEquals("AUTHORIZED", queryString("select status from payments where order_id = '" + orderId + "'"));
            assertEquals("CARD", queryString("select method from payments where order_id = '" + orderId + "'"));
            assertEquals(1, countProcessedEvents(commandEventId));
            assertEquals("PENDING", queryString("select status from outbox_events where aggregate_id = '" + orderId + "'"));
            assertEquals(orderId, queryString("select payload ->> 'orderId' from outbox_events where aggregate_id = '" + orderId + "'"));

            OutboxRelayResult result = relayService.publishPending(10);

            assertEquals(1, result.claimed());
            assertEquals(1, result.published());
            assertEquals(0, result.failed());

            JsonNode paymentEvent = pollPaymentEvent(consumer, orderId);
            assertEquals("PaymentAuthorized", paymentEvent.path("eventType").stringValue());
            assertEquals("payment", paymentEvent.path("aggregateType").stringValue());
            assertEquals(orderId, paymentEvent.path("aggregateId").stringValue());
            assertEquals("payment-service", paymentEvent.path("sourceService").stringValue());
            assertEquals(orderId, paymentEvent.path("payload").path("orderId").stringValue());
        }
    }

    private void waitForListenerAssignment() {
        int partitionCount = partitionCount(PAYMENT_COMMANDS_TOPIC);
        listenerEndpointRegistry.getListenerContainers()
            .forEach(container -> ContainerTestUtils.waitForAssignment(container, partitionCount));
    }

    private KafkaConsumer<String, String> paymentEventsConsumer() {
        int partitionCount = partitionCount(PAYMENT_EVENTS_TOPIC);
        Properties props = consumerProperties("payment-smoke-" + UUID.randomUUID(), "earliest");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(PAYMENT_EVENTS_TOPIC));
        long deadline = System.currentTimeMillis() + 10_000;
        while (consumer.assignment().size() < partitionCount && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertEquals(partitionCount, consumer.assignment().size());
        return consumer;
    }

    private int partitionCount(String topic) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
            consumerProperties("payment-smoke-metadata-" + UUID.randomUUID(), "earliest")
        )) {
            return consumer.partitionsFor(topic, Duration.ofSeconds(10)).size();
        }
    }

    private Properties consumerProperties(String groupId, String autoOffsetReset) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:19092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    private JsonNode pollPaymentEvent(KafkaConsumer<String, String> consumer, String orderId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            for (var record : records) {
                JsonNode event = objectMapper.readTree(record.value());
                if (orderId.equals(event.path("aggregateId").stringValue())) {
                    return event;
                }
            }
        }
        throw new AssertionError("payment event was not published to Kafka");
    }

    private String paymentAuthorizationRequestedJson(UUID eventId, String orderId) {
        return """
            {
              "eventId": "%s",
              "eventType": "PaymentAuthorizationRequested",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "%s",
              "correlationId": "corr-payment-kafka-smoke-001",
              "causationId": null,
              "idempotencyKey": "idem-payment-kafka-smoke-%s",
              "occurredAt": "2026-05-12T16:00:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "%s",
                "amount": 24000.00,
                "method": "CARD"
              }
            }
            """.formatted(eventId, orderId, orderId, orderId);
    }

    private int countOutbox(String orderId, String eventType) {
        return jdbcClient.sql("""
                select count(*)
                from outbox_events
                where aggregate_id = :orderId
                  and event_type = :eventType
                """)
            .param("orderId", orderId)
            .param("eventType", eventType)
            .query(Integer.class)
            .single();
    }

    private int countPayments(String orderId) {
        return jdbcClient.sql("select count(*) from payments where order_id = :orderId")
            .param("orderId", orderId)
            .query(Integer.class)
            .single();
    }

    private int countProcessedEvents(UUID eventId) {
        return jdbcClient.sql("select count(*) from processed_events where event_id = :eventId")
            .param("eventId", eventId)
            .query(Integer.class)
            .single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }

    private void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
