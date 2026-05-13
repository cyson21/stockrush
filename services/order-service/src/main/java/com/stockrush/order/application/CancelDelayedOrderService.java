package com.stockrush.order.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelDelayedOrderService {

    private static final String PAYMENT_COMMAND_TOPIC = "stockrush.payment.commands.v1";
    private static final String CANCEL_REASON = "ADMIN_CANCEL_REQUESTED";

    private final JdbcClient jdbcClient;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;
    private final Supplier<UUID> eventIdSupplier;

    public CancelDelayedOrderService(
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
    public CancelDelayedOrderResult cancel(String orderId, String idempotencyKey, String correlationId) {
        if (isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }

        OrderState state = findOrderForUpdate(orderId);
        if ("CREATED".equals(state.status()) && "PAYMENT_CANCEL_REQUESTED".equals(state.sagaStatus())) {
            return new CancelDelayedOrderResult(orderId, "CREATED", "PAYMENT_CANCEL_REQUESTED");
        }
        if (!"CREATED".equals(state.status()) || !"PAYMENT_DELAYED".equals(state.sagaStatus())) {
            throw new IllegalArgumentException("Only payment delayed orders can be canceled.");
        }

        Instant now = clock.instant();
        jdbcClient.sql("""
                update customer_orders
                set saga_status = 'PAYMENT_CANCEL_REQUESTED',
                    updated_at = now()
                where order_id = :orderId
                """)
            .param("orderId", orderId)
            .update();

        outboxEventRepository.save(new OutboxEventRecord<>(
            eventIdSupplier.get(),
            "PaymentCancelRequested",
            1,
            "order",
            orderId,
            correlationId,
            null,
            idempotencyKey,
            now,
            "order-service",
            OutboxEventStatus.PENDING,
            PAYMENT_COMMAND_TOPIC,
            orderId,
            new PaymentCancelRequestedPayload(orderId, CANCEL_REASON, now)
        ));

        return new CancelDelayedOrderResult(orderId, "CREATED", "PAYMENT_CANCEL_REQUESTED");
    }

    private OrderState findOrderForUpdate(String orderId) {
        return jdbcClient.sql("""
                select status, saga_status
                from customer_orders
                where order_id = :orderId
                for update
                """)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new OrderState(rs.getString("status"), rs.getString("saga_status")))
            .optional()
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record OrderState(String status, String sagaStatus) {
    }
}
