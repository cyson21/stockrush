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
        CreateOrderResult result = createOrderService.create(command);
        orderRepository.save(result.order(), command.idempotencyKey());
        outboxEventRepository.save(result.outboxEvent());
        return result;
    }
}

