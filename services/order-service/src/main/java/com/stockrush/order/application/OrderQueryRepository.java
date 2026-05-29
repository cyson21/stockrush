// OrderQueryRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.util.List;
import java.util.Optional;

public interface OrderQueryRepository {

    Optional<OrderDetailSnapshot> findByOrderId(String orderId);

    List<OrderSummarySnapshot> findRecentOrders(int page, int size, OrderStatus status, SagaStatus sagaStatus);

    Optional<OrderSagaSnapshot> findSagaByOrderId(String orderId);
}
