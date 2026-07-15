// OrderForbiddenException: 도메인 예외를 명시적으로 구분해 오류 경로를 명확히 표현합니다.

package com.stockrush.order.application;

public class OrderForbiddenException extends RuntimeException {

    public OrderForbiddenException(String orderId) {
        super("Order access is forbidden: " + orderId);
    }
}
