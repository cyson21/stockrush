package com.stockrush.inventory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.inventory.infra.kafka.KafkaEventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=inventory",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class InventoryReservationHandlerIntegrationTest {

    private static final UUID ORDER_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b01");

    @Autowired
    private InventoryReservationHandler handler;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from stock_reservations").update();
        jdbcClient.sql("delete from stock_items").update();
        jdbcClient.sql("""
                insert into stock_items (sku_id, product_code, available_quantity, reserved_quantity, created_at, updated_at)
                values ('SKU-001', 'LIMITED-001', 5, 0, now(), now())
                """)
            .update();
    }

    @Test
    void reserves_stock_and_writes_outbox_when_order_created() {
        handler.handle(orderCreated());

        assertEquals(3, queryInt("select available_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(2, queryInt("select reserved_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(1, queryInt("select count(*) from stock_reservations where order_id = 'ord_inventory_001'"));
        assertEquals("RESERVED", queryString("select status from stock_reservations where order_id = 'ord_inventory_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_EVENT_ID + "'"));
        assertEquals("InventoryReserved", queryString("select event_type from outbox_events"));
        assertEquals("PENDING", queryString("select status from outbox_events"));
    }

    private KafkaEventEnvelope<OrderCreatedPayload> orderCreated() {
        return new KafkaEventEnvelope<>(
            ORDER_EVENT_ID,
            "OrderCreated",
            1,
            "order",
            "ord_inventory_001",
            "corr-inventory-001",
            null,
            "idem-inventory-001",
            Instant.parse("2026-05-12T15:00:00Z"),
            "order-service",
            new OrderCreatedPayload(
                "ord_inventory_001",
                "member-1",
                List.of(new OrderCreatedItemPayload("LIMITED-001", "SKU-001", 2, new BigDecimal("12000.00"))),
                new BigDecimal("24000.00"),
                Instant.parse("2026-05-12T15:00:00Z")
            )
        );
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
