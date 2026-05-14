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
            return CreateOrderResult.replayed(existingOrder.get());
        }

        CreateOrderResult result = createOrderService.create(command);
        boolean saved = orderRepository.saveIfAbsent(result.order(), command.idempotencyKey());
        if (!saved) {
            return CreateOrderResult.replayed(replayOrder(command.idempotencyKey()));
        }
        outboxEventRepository.save(result.outboxEvent());
        return result;
    }

    private OrderSnapshot replayOrder(String idempotencyKey) {
        var existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOrder.isPresent()) {
            return existingOrder.get();
        }
        throw new OrderIdempotencyReplayUnavailableException("Idempotent order replay is not available yet.");
    }
}
