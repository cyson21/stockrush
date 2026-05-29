// StockNotFoundException: 도메인 예외를 명시적으로 구분해 오류 경로를 명확히 표현합니다.

package com.stockrush.inventory.application;

public class StockNotFoundException extends RuntimeException {

    public StockNotFoundException(String skuId) {
        super("Stock item not found: " + skuId);
    }
}
