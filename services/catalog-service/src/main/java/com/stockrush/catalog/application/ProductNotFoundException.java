package com.stockrush.catalog.application;

public class ProductNotFoundException extends RuntimeException {

    private final String productCode;

    public ProductNotFoundException(String productCode) {
        super("Product not found: " + productCode);
        this.productCode = productCode;
    }

    public String productCode() {
        return productCode;
    }
}
