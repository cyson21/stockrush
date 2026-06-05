// OrderSagaEventHandler: 이벤트/메시지 처리 흐름을 수신하고 도메인 상태 반영을 담당합니다.

package com.stockrush.order.application;

import com.stockrush.order.infra.kafka.KafkaEventEnvelope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderSagaEventHandler {

    private static final String CONSUMER_GROUP = "order-service";
    private static final String ORDER_TOPIC = "stockrush.order.events.v1";
    private static final String PAYMENT_COMMAND_TOPIC = "stockrush.payment.commands.v1";

    private final JdbcClient jdbcClient;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;
    private final Supplier<UUID> eventIdSupplier;

    public OrderSagaEventHandler(
        JdbcClient jdbcClient,
        OutboxEventRepository outboxEventRepository,
        Clock clock,
        Supplier<UUID> eventIdSupplier
    ) {
        this.jdbcClient = jdbcClient;
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
        this.eventIdSupplier = eventIdSupplier;
    }

    @Transactional
    public void handleInventoryReserved(KafkaEventEnvelope<InventoryReservedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        String orderId = event.payload().orderId();
        if (!transitionOrder(orderId, "CREATED", "PAYMENT_REQUESTED")) {
            return;
        }
        PaymentRequestOrder order = paymentRequestOrder(orderId);
        saveOutbox(
            event,
            "PaymentAuthorizationRequested",
            PAYMENT_COMMAND_TOPIC,
            new PaymentAuthorizationRequestedPayload(orderId, order.payableAmount(), order.paymentMethod())
        );
    }

    @Transactional
    public void handleInventoryReservationFailed(KafkaEventEnvelope<InventoryReservationFailedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        String orderId = event.payload().orderId();
        if (!cancelOrder(orderId)) {
            return;
        }
        saveOutbox(
            event,
            "OrderCancelled",
            ORDER_TOPIC,
            new OrderCancelledPayload(orderId, event.payload().reason(), clock.instant())
        );
    }

    @Transactional
    public void handlePaymentAuthorized(KafkaEventEnvelope<PaymentAuthorizedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        String orderId = event.payload().orderId();
        if (!transitionOrder(orderId, "CONFIRMED", "COMPLETED")) {
            return;
        }
        saveOutbox(
            event,
            "OrderConfirmed",
            ORDER_TOPIC,
            new OrderConfirmedPayload(orderId, clock.instant())
        );
    }

    @Transactional
    public void handlePaymentAuthorizationFailed(KafkaEventEnvelope<PaymentAuthorizationFailedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        String orderId = event.payload().orderId();
        if (!cancelOrder(orderId)) {
            return;
        }
        saveOutbox(
            event,
            "OrderCancelled",
            ORDER_TOPIC,
            new OrderCancelledPayload(orderId, event.payload().reason(), clock.instant())
        );
    }

    @Transactional
    public void handlePaymentAuthorizationDelayed(KafkaEventEnvelope<PaymentAuthorizationDelayedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        transitionOrder(event.payload().orderId(), "CREATED", "PAYMENT_DELAYED");
    }

    @Transactional
    public void handlePaymentCanceled(KafkaEventEnvelope<PaymentCanceledPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        String orderId = event.payload().orderId();
        if (!cancelOrder(orderId)) {
            return;
        }
        saveOutbox(
            event,
            "OrderCancelled",
            ORDER_TOPIC,
            new OrderCancelledPayload(orderId, event.payload().reason(), clock.instant())
        );
    }

    private boolean markProcessed(KafkaEventEnvelope<?> event) {
        int inserted = jdbcClient.sql("""
                insert into processed_events (
                  event_id, consumer_group, event_type, aggregate_id, idempotency_key, processed_at
                )
                values (:eventId, :consumerGroup, :eventType, :aggregateId, :idempotencyKey, now())
                on conflict do nothing
                """)
            .param("eventId", event.eventId())
            .param("consumerGroup", CONSUMER_GROUP)
            .param("eventType", event.eventType())
            .param("aggregateId", event.aggregateId())
            .param("idempotencyKey", event.idempotencyKey())
            .update();

        return inserted == 1;
    }

    private PaymentRequestOrder paymentRequestOrder(String orderId) {
        return jdbcClient.sql("""
                select payable_amount, payment_method
                from customer_orders
                where order_id = :orderId
                """)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new PaymentRequestOrder(
                rs.getBigDecimal("payable_amount"),
                rs.getString("payment_method")
            ))
            .single();
    }

    /**
     * 종료 상태(CANCELLED/CONFIRMED) 주문은 더 이상 전이시키지 않는다.
     * 늦게 도착한 이벤트가 이미 끝난 주문을 되살리는 것을 막고, 전이 성공 여부를 반환한다.
     */
    private boolean transitionOrder(String orderId, String status, String sagaStatus) {
        int updated = jdbcClient.sql("""
                update customer_orders
                set status = :status,
                    saga_status = :sagaStatus,
                    updated_at = now()
                where order_id = :orderId
                  and status not in ('CANCELLED', 'CONFIRMED')
                """)
            .param("status", status)
            .param("sagaStatus", sagaStatus)
            .param("orderId", orderId)
            .update();

        return updated == 1;
    }

    private boolean cancelOrder(String orderId) {
        return transitionOrder(orderId, "CANCELLED", "FAILED");
    }

    private void saveOutbox(KafkaEventEnvelope<?> source, String eventType, String topic, Object payload) {
        Instant now = clock.instant();
        outboxEventRepository.save(new OutboxEventRecord<>(
            eventIdSupplier.get(),
            eventType,
            1,
            "order",
            source.aggregateId(),
            source.correlationId(),
            source.eventId(),
            source.idempotencyKey(),
            now,
            "order-service",
            OutboxEventStatus.PENDING,
            topic,
            source.aggregateId(),
            payload
        ));
    }

    private record PaymentRequestOrder(BigDecimal payableAmount, String paymentMethod) {
    }
}
