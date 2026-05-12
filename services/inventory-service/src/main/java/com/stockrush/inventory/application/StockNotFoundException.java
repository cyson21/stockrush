package com.stockrush.inventory.application;

public class StockNotFoundException extends RuntimeException {

    public StockNotFoundException(String skuId) {
        super("Stock item not found: " + skuId);
    }
}
