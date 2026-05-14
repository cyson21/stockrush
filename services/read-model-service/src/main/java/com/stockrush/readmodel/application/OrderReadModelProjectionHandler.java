package com.stockrush.readmodel.application;

import com.stockrush.readmodel.infra.kafka.KafkaEventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderReadModelProjectionHandler {

    private static final String CONSUMER_GROUP = "read-model-service";

    private final JdbcClient jdbcClient;

    public OrderReadModelProjectionHandler(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void handleOrderCreated(KafkaEventEnvelope<OrderCreatedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        OrderCreatedPayload payload = event.payload();
        validateOrderCreated(payload);

        jdbcClient.sql("""
                insert into order_summaries (
                  order_id, member_id, status, saga_status, coupon_code, total_amount,
                  discount_amount, payable_amount, item_count, created_at, updated_at
                )
                values (
                  :orderId, :memberId, 'CREATED', 'STARTED', :couponCode, :totalAmount,
                  :discountAmount, :payableAmount, :itemCount, :createdAt, :createdAt
                )
                on conflict (order_id) do nothing
                """)
            .param("orderId", payload.orderId())
            .param("memberId", payload.memberId())
            .param("couponCode", payload.couponCode())
            .param("totalAmount", payload.totalAmount())
            .param("discountAmount", payload.discountAmount())
            .param("payableAmount", payload.payableAmount())
            .param("itemCount", payload.items().size())
            .param("createdAt", toOffsetDateTime(payload.createdAt()))
            .update();
    }

    @Transactional
    public void handleOrderConfirmed(KafkaEventEnvelope<OrderConfirmedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        OrderConfirmedPayload payload = event.payload();
        validateOrderId(payload.orderId());

        int updated = jdbcClient.sql("""
                update order_summaries
                set status = 'CONFIRMED',
                    saga_status = 'COMPLETED',
                    confirmed_at = :confirmedAt,
                    updated_at = :confirmedAt
                where order_id = :orderId
                """)
            .param("orderId", payload.orderId())
            .param("confirmedAt", toOffsetDateTime(payload.confirmedAt()))
            .update();
        requireExistingSummary(updated, payload.orderId(), event.eventType());
    }

    @Transactional
    public void handleOrderCancelled(KafkaEventEnvelope<OrderCancelledPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        OrderCancelledPayload payload = event.payload();
        validateOrderId(payload.orderId());

        int updated = jdbcClient.sql("""
                update order_summaries
                set status = 'CANCELLED',
                    saga_status = 'FAILED',
                    cancellation_reason = :reason,
                    cancelled_at = :cancelledAt,
                    updated_at = :cancelledAt
                where order_id = :orderId
                """)
            .param("orderId", payload.orderId())
            .param("reason", payload.reason())
            .param("cancelledAt", toOffsetDateTime(payload.cancelledAt()))
            .update();
        requireExistingSummary(updated, payload.orderId(), event.eventType());
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

    private void validateOrderCreated(OrderCreatedPayload payload) {
        validateOrderId(payload.orderId());
        if (payload.memberId() == null || payload.memberId().isBlank()) {
            throw new IllegalArgumentException("memberId must not be blank");
        }
        if (payload.items() == null || payload.items().isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        validateAmount(payload.totalAmount(), "totalAmount");
        if (payload.discountAmount() == null || payload.discountAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("discountAmount must not be negative");
        }
        if (payload.payableAmount() == null || payload.payableAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("payableAmount must not be negative");
        }
        if (payload.createdAt() == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }

    private void validateAmount(BigDecimal amount, String fieldName) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private void validateOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
    }

    private void requireExistingSummary(int updated, String orderId, String eventType) {
        if (updated == 0) {
            throw new IllegalStateException("order summary is not ready for " + eventType + ": " + orderId);
        }
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("event timestamp must not be null");
        }
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
