// OrderQueryService: 비즈니스 핵심 흐름을 조합해 상태 변경과 유효성 규칙을 적용합니다.

package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.util.Locale;
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

    @Transactional(readOnly = true)
    public OrderDetailSnapshot getDetailForMember(String orderId, String memberId) {
        OrderDetailSnapshot snapshot = getDetail(orderId);
        String normalizedMemberId = normalizeRequired(memberId, "memberId");
        if (!snapshot.memberId().equals(normalizedMemberId)) {
            throw new OrderForbiddenException(orderId);
        }
        return snapshot;
    }

    @Transactional(readOnly = true)
    public OrderPageSnapshot listRecentOrders(int page, int size, String status, String sagaStatus) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }

        OrderStatus parsedStatus = parseEnum(OrderStatus.class, status, "status");
        SagaStatus parsedSagaStatus = parseEnum(SagaStatus.class, sagaStatus, "sagaStatus");

        return new OrderPageSnapshot(
            page,
            size,
            orderQueryRepository.findRecentOrders(page, size, parsedStatus, parsedSagaStatus)
        );
    }

    @Transactional(readOnly = true)
    public OrderSagaSnapshot getSaga(String orderId) {
        return orderQueryRepository.findSagaByOrderId(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid " + fieldName + ": " + value, exception);
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
