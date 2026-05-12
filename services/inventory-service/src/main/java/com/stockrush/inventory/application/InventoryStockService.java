package com.stockrush.inventory.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryStockService {

    private final StockRepository stockRepository;

    public InventoryStockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public List<StockSnapshot> list(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return stockRepository.findAll();
        }
        return stockRepository.findByProductCode(productCode);
    }

    public StockSnapshot get(String skuId) {
        return stockRepository.findBySkuId(skuId)
            .orElseThrow(() -> new StockNotFoundException(skuId));
    }

    @Transactional
    public StockSnapshot setAvailableQuantity(String skuId, String productCode, int availableQuantity) {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must be greater than or equal to 0");
        }
        return stockRepository.setAvailableQuantity(skuId, productCode, availableQuantity);
    }
}
