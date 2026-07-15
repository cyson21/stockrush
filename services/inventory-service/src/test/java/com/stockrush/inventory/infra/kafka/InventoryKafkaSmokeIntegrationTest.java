// InventoryKafkaSmokeIntegrationTest: 이벤트 인입·전송 경계에서 메시지 처리 순서를 보존합니다.

package com.stockrush.inventory.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockrush.inventory.infra.outbox.OutboxRelayResult;
import com.stockrush.inventory.infra.outbox.OutboxRelayService;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=inventory",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush",
    "spring.kafka.bootstrap-servers=localhost:19092",
    "spring.kafka.consumer.auto-offset-reset=latest",
    "stockrush.kafka.consumer.group-id=inventory-smoke-listener-${random.uuid}",
    "stockrush.kafka.listeners.enabled=true"
})
class InventoryKafkaSmokeIntegrationTest {

    private static final String ORDER_EVENTS_TOPIC = "stockrush.order.events.v1";
    private static final String INVENTORY_EVENTS_TOPIC = "stockrush.inventory.events.v1";
    private static final String SKU_ID = "SKU-KAFKA-SMOKE-001";

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
        jdbcClient.sql("delete from stock_reservations").update();
        jdbcClient.sql("delete from stock_items where sku_id = :skuId")
            .param("skuId", SKU_ID)
            .update();
        jdbcClient.sql("""
                insert into stock_items (sku_id, product_code, available_quantity, reserved_quantity, created_at, updated_at)
                values (:skuId, 'LIMITED-KAFKA-SMOKE', 5, 0, now(), now())
                """)
            .param("skuId", SKU_ID)
            .update();
    }

    @Test
    void consumes_order_created_and_relay_publishes_inventory_reserved() throws Exception {
        waitForListenerAssignment();
        String orderId = "ord_kafka_smoke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID orderEventId = UUID.randomUUID();

        try (KafkaConsumer<String, String> consumer = inventoryEventsConsumer()) {
            kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, orderCreatedJson(orderEventId, orderId))
                .get(10, TimeUnit.SECONDS);

            await(() -> countOutbox(orderId, "InventoryReserved") == 1, "inventory outbox event was not created");

            assertEquals(3, queryInt("select available_quantity from stock_items where sku_id = '" + SKU_ID + "'"));
            assertEquals(2, queryInt("select reserved_quantity from stock_items where sku_id = '" + SKU_ID + "'"));
            assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + orderEventId + "'"));

            OutboxRelayResult result = relayService.publishPending(10);

            assertEquals(1, result.claimed());
            assertEquals(1, result.published());
            assertEquals(0, result.failed());

            JsonNode inventoryEvent = pollInventoryEvent(consumer, orderId);
            assertEquals("InventoryReserved", inventoryEvent.path("eventType").stringValue());
            assertEquals("inventory-reservation", inventoryEvent.path("aggregateType").stringValue());
            assertEquals(orderId, inventoryEvent.path("aggregateId").stringValue());
            assertEquals("inventory-service", inventoryEvent.path("sourceService").stringValue());
            assertEquals(orderId, inventoryEvent.path("payload").path("orderId").stringValue());
            assertEquals(1, inventoryEvent.path("payload").path("items").size());
        }
    }

    private void waitForListenerAssignment() {
        int partitionCount = partitionCount(ORDER_EVENTS_TOPIC);
        listenerEndpointRegistry.getListenerContainers()
            .forEach(container -> ContainerTestUtils.waitForAssignment(container, partitionCount));
    }

    private KafkaConsumer<String, String> inventoryEventsConsumer() {
        int partitionCount = partitionCount(INVENTORY_EVENTS_TOPIC);
        Properties props = consumerProperties("inventory-smoke-" + UUID.randomUUID(), "earliest");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(INVENTORY_EVENTS_TOPIC));
        long deadline = System.currentTimeMillis() + 10_000;
        while (consumer.assignment().size() < partitionCount && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertEquals(partitionCount, consumer.assignment().size());
        return consumer;
    }

    private int partitionCount(String topic) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
            consumerProperties("inventory-smoke-metadata-" + UUID.randomUUID(), "earliest")
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

    private JsonNode pollInventoryEvent(KafkaConsumer<String, String> consumer, String orderId) throws Exception {
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
        throw new AssertionError("inventory event was not published to Kafka");
    }

    private String orderCreatedJson(UUID eventId, String orderId) {
        return """
            {
              "eventId": "%s",
              "eventType": "OrderCreated",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "%s",
              "correlationId": "corr-kafka-smoke-001",
              "causationId": null,
              "idempotencyKey": "idem-kafka-smoke-%s",
              "occurredAt": "2026-05-12T16:00:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "%s",
                "memberId": "member-kafka-smoke",
                "items": [
                  {
                    "productCode": "LIMITED-KAFKA-SMOKE",
                    "skuId": "%s",
                    "quantity": 2,
                    "unitPrice": 12000.00
                  }
                ],
                "totalAmount": 24000.00,
                "createdAt": "2026-05-12T16:00:00Z"
              }
            }
            """.formatted(eventId, orderId, orderId, orderId, SKU_ID);
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

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
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
