package com.stockrush.order.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistentCreateOrderService {

    private final CreateOrderService createOrderService;
    private final OrderCommandRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;

    public PersistentCreateOrderService(
        CreateOrderService createOrderService,
        OrderCommandRepository orderRepository,
        OutboxEventRepository outboxEventRepository
    ) {
        this.createOrderService = createOrderService;
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public CreateOrderResult create(CreateOrderCommand command) {
        var existingOrder = orderRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existingOrder.isPresent()) {
            return CreateOrderResult.replayed(ownedReplay(existingOrder.get(), command.memberId()));
        }

        CreateOrderResult result = createOrderService.create(command);
        boolean saved = orderRepository.saveIfAbsent(result.order(), command.idempotencyKey());
        if (!saved) {
            return CreateOrderResult.replayed(replayOrder(command.idempotencyKey(), command.memberId()));
        }
        outboxEventRepository.save(result.outboxEvent());
        return result;
    }

    private OrderSnapshot replayOrder(String idempotencyKey, String memberId) {
        var existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOrder.isPresent()) {
            return ownedReplay(existingOrder.get(), memberId);
        }
        throw new OrderIdempotencyReplayUnavailableException("Idempotent order replay is not available yet.");
    }

    private OrderSnapshot ownedReplay(OrderSnapshot existingOrder, String memberId) {
        if (!existingOrder.memberId().equals(memberId)) {
            throw new OrderForbiddenException("Order does not belong to the authenticated customer.");
        }
        return existingOrder;
    }
}
