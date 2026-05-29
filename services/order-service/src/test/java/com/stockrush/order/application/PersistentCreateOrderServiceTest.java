// PersistentCreateOrderServiceTest: 비즈니스 핵심 흐름을 조합해 상태 변경과 유효성 규칙을 적용합니다.

package com.stockrush.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersistentCreateOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-14T08:00:00Z");

    @Test
    void returns_retryable_error_when_conflict_row_is_not_visible_yet() {
        OrderSnapshot existingOrder = order("ord-existing");
        DelayedReplayOrderRepository orderRepository = new DelayedReplayOrderRepository(existingOrder, 3);
        RecordingOutboxEventRepository outboxEventRepository = new RecordingOutboxEventRepository();
        PersistentCreateOrderService service = new PersistentCreateOrderService(
            new CreateOrderService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7a22"),
                () -> "ord-new"
            ),
            orderRepository,
            outboxEventRepository
        );

        assertThrows(OrderIdempotencyReplayUnavailableException.class, () -> service.create(command()));

        assertEquals(2, orderRepository.findAttempts);
        assertEquals(0, outboxEventRepository.saveCount);
    }

    @Test
    void replays_conflict_winner_when_row_is_visible_after_insert_conflict() {
        OrderSnapshot existingOrder = order("ord-existing");
        DelayedReplayOrderRepository orderRepository = new DelayedReplayOrderRepository(existingOrder, 2);
        RecordingOutboxEventRepository outboxEventRepository = new RecordingOutboxEventRepository();
        PersistentCreateOrderService service = new PersistentCreateOrderService(
            new CreateOrderService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7a22"),
                () -> "ord-new"
            ),
            orderRepository,
            outboxEventRepository
        );

        CreateOrderResult result = service.create(command());

        assertTrue(result.replayed());
        assertEquals("ord-existing", result.order().orderId());
        assertEquals(2, orderRepository.findAttempts);
        assertEquals(0, outboxEventRepository.saveCount);
    }

    private static CreateOrderCommand command() {
        return new CreateOrderCommand(
            "member-1",
            "idem-replay-race",
            "corr-replay-race",
            "CARD",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 1, new BigDecimal("12000.00")))
        );
    }

    private static OrderSnapshot order(String orderId) {
        return new OrderSnapshot(
            orderId,
            "member-1",
            OrderStatus.CREATED,
            SagaStatus.STARTED,
            "CARD",
            new BigDecimal("12000.00"),
            NOW,
            List.of(new OrderLineSnapshot("LIMITED-001", "SKU-001", 1, new BigDecimal("12000.00"), new BigDecimal("12000.00")))
        );
    }

    private static class DelayedReplayOrderRepository implements OrderCommandRepository {

        private final OrderSnapshot existingOrder;
        private final int visibleAtAttempt;
        private int findAttempts;

        private DelayedReplayOrderRepository(OrderSnapshot existingOrder, int visibleAtAttempt) {
            this.existingOrder = existingOrder;
            this.visibleAtAttempt = visibleAtAttempt;
        }

        @Override
        public boolean saveIfAbsent(OrderSnapshot order, String idempotencyKey) {
            return false;
        }

        @Override
        public Optional<OrderSnapshot> findByIdempotencyKey(String idempotencyKey) {
            findAttempts++;
            if (findAttempts < visibleAtAttempt) {
                return Optional.empty();
            }
            return Optional.of(existingOrder);
        }
    }

    private static class RecordingOutboxEventRepository implements OutboxEventRepository {

        private int saveCount;

        @Override
        public void save(OutboxEventRecord<?> event) {
            saveCount++;
        }
    }
}
