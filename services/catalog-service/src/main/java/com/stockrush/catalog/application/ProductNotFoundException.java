// ProductNotFoundException: 도메인 예외를 명시적으로 구분해 오류 경로를 명확히 표현합니다.

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
