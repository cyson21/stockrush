package com.stockrush.inventory.application;

import java.util.List;
import java.util.Optional;

public interface StockRepository {

    List<StockSnapshot> findAll();

    List<StockSnapshot> findByProductCode(String productCode);

    Optional<StockSnapshot> findBySkuId(String skuId);

    StockSnapshot setAvailableQuantity(String skuId, String productCode, int availableQuantity);
}
