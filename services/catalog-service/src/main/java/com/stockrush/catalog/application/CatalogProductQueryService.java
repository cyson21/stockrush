package com.stockrush.catalog.application;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CatalogProductQueryService {

    private final ProductQueryRepository productQueryRepository;

    public CatalogProductQueryService(ProductQueryRepository productQueryRepository) {
        this.productQueryRepository = productQueryRepository;
    }

    public List<ProductSnapshot> listByStatus(String status) {
        return productQueryRepository.findByStatus(status);
    }

    public ProductSnapshot getByProductCode(String productCode) {
        return productQueryRepository.findByProductCode(productCode)
            .orElseThrow(() -> new ProductNotFoundException(productCode));
    }
}
