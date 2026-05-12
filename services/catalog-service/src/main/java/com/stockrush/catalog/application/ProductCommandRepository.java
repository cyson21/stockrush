package com.stockrush.catalog.application;

import java.math.BigDecimal;
import java.util.Optional;

public interface ProductCommandRepository {

    ProductSnapshot create(ProductSnapshot product);

    Optional<ProductSnapshot> update(String productCode, String name, String salesStatus, BigDecimal listPrice);
}
