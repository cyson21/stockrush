package com.stockrush.order.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {

    private final OrderQueryRepository orderQueryRepository;

    public OrderQueryService(OrderQueryRepository orderQueryRepository) {
        this.orderQueryRepository = orderQueryRepository;
    }

    @Transactional(readOnly = true)
    public OrderDetailSnapshot getDetail(String orderId) {
        return orderQueryRepository.findByOrderId(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
