package com.stockrush.catalog.application;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CatalogProductQueryService {

    private final ProductQueryRepository productQueryRepository;

    public CatalogProductQueryService(ProductQueryRepository productQueryRepository) {
        this.productQueryRepository = productQueryRepository;
    }

    public List<ProductSnapshot> listByStatus(String status, String query) {
        String normalizedQuery = query == null ? null : query.trim();
        if (normalizedQuery != null && normalizedQuery.isBlank()) {
            normalizedQuery = null;
        }

        return productQueryRepository.findByStatus(status, normalizedQuery);
    }

    public List<ProductSnapshot> listByStatus(String status) {
        return listByStatus(status, null);
    }

    public ProductSnapshot getByProductCode(String productCode) {
        return productQueryRepository.findByProductCode(productCode)
            .orElseThrow(() -> new ProductNotFoundException(productCode));
    }
}
