package com.stockrush.catalog.application;

import java.util.List;
import java.util.Optional;

public interface ProductQueryRepository {

    List<ProductSnapshot> findByStatus(String status);

    Optional<ProductSnapshot> findByProductCode(String productCode);
}
