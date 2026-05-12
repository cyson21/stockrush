package com.stockrush.catalog.application;

public class DuplicateProductException extends RuntimeException {

    private final String productCode;

    public DuplicateProductException(String productCode) {
        super("Duplicate product code: " + productCode);
        this.productCode = productCode;
    }

    public String productCode() {
        return productCode;
    }
}
