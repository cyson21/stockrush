package com.stockrush.promotion.application;

import com.stockrush.promotion.infra.kafka.KafkaEventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * 외부 이벤트/명령을 수신해 유효성, 멱등성 체크, 다음 상태 전환을 조합해 처리하는 핸들러입니다.
 */


@Service
public class PromotionCouponUsageEventHandler {

    private static final String CONSUMER_GROUP = "promotion-service";

    private final JdbcClient jdbcClient;

    public PromotionCouponUsageEventHandler(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void handleOrderCreated(KafkaEventEnvelope<OrderCreatedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        OrderCreatedPayload payload = event.payload();
        if (payload.couponCode() == null || payload.couponCode().isBlank()) {
            return;
        }
        validateCouponUsagePayload(payload);

        jdbcClient.sql("""
                insert into coupon_usages (
                  order_id, member_id, coupon_code, status, order_amount, discount_amount,
                  payable_amount, reserved_at, created_at, updated_at
                )
                values (
                  :orderId, :memberId, :couponCode, 'RESERVED', :orderAmount, :discountAmount,
                  :payableAmount, :reservedAt, now(), now()
                )
                on conflict (order_id) do nothing
                """)
            .param("orderId", payload.orderId())
            .param("memberId", payload.memberId())
            .param("couponCode", payload.couponCode())
            .param("orderAmount", payload.totalAmount())
            .param("discountAmount", payload.discountAmount())
            .param("payableAmount", payload.payableAmount())
            .param("reservedAt", toOffsetDateTime(payload.createdAt()))
            .update();
    }

    @Transactional
    public void handleOrderConfirmed(KafkaEventEnvelope<OrderConfirmedPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        jdbcClient.sql("""
                update coupon_usages
                set status = 'CONSUMED',
                    consumed_at = coalesce(consumed_at, :consumedAt),
                    updated_at = now()
                where order_id = :orderId
                  and status = 'RESERVED'
                """)
            .param("orderId", event.payload().orderId())
            .param("consumedAt", toOffsetDateTime(event.payload().confirmedAt()))
            .update();
    }

    @Transactional
    public void handleOrderCancelled(KafkaEventEnvelope<OrderCancelledPayload> event) {
        if (!markProcessed(event)) {
            return;
        }

        jdbcClient.sql("""
                update coupon_usages
                set status = 'RELEASED',
                    released_at = coalesce(released_at, :releasedAt),
                    release_reason = :releaseReason,
                    updated_at = now()
                where order_id = :orderId
                  and status = 'RESERVED'
                """)
            .param("orderId", event.payload().orderId())
            .param("releasedAt", toOffsetDateTime(event.payload().cancelledAt()))
            .param("releaseReason", event.payload().reason())
            .update();
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

    private void validateCouponUsagePayload(OrderCreatedPayload payload) {
        if (payload.orderId() == null || payload.orderId().isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (payload.memberId() == null || payload.memberId().isBlank()) {
            throw new IllegalArgumentException("memberId must not be blank");
        }
        if (payload.totalAmount() == null || payload.totalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("totalAmount must be positive");
        }
        if (payload.discountAmount() == null || payload.discountAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("discountAmount must be positive");
        }
        if (payload.payableAmount() == null || payload.payableAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("payableAmount must not be negative");
        }
        if (payload.payableAmount().add(payload.discountAmount()).compareTo(payload.totalAmount()) != 0) {
            throw new IllegalArgumentException("coupon usage amount is inconsistent");
        }
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
