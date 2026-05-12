package com.stockrush.catalog.application;

import java.math.BigDecimal;
import java.sql.SQLException;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class CatalogProductAdminService {

    private final ProductQueryRepository productQueryRepository;
    private final ProductCommandRepository productCommandRepository;

    CatalogProductAdminService(
        ProductQueryRepository productQueryRepository,
        ProductCommandRepository productCommandRepository
    ) {
        this.productQueryRepository = productQueryRepository;
        this.productCommandRepository = productCommandRepository;
    }

    public ProductSnapshot create(String productCode, String name, BigDecimal listPrice, String salesStatus) {
        if (productCode == null || productCode.isBlank()) {
            throw new IllegalArgumentException("productCode must not be blank");
        }
        if (productQueryRepository.findByProductCode(productCode).isPresent()) {
            throw new DuplicateProductException(productCode);
        }

        try {
            return productCommandRepository.create(new ProductSnapshot(productCode, name, salesStatus, listPrice));
        } catch (DataIntegrityViolationException exception) {
            if (isProductCodeUniqueViolation(exception)) {
                throw new DuplicateProductException(productCode);
            }
            throw new CatalogDataIntegrityException("Catalog product data integrity error.", exception);
        }
    }

    public ProductSnapshot update(String productCode, String name, BigDecimal listPrice, String salesStatus) {
        if (productCode == null || productCode.isBlank()) {
            throw new IllegalArgumentException("productCode must not be blank");
        }

        return productCommandRepository.update(productCode, name, salesStatus, listPrice)
            .orElseThrow(() -> new ProductNotFoundException(productCode));
    }

    private boolean isProductCodeUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String message = sqlException.getMessage();
                return "23505".equals(sqlException.getSQLState())
                    && message != null
                    && message.contains("product_code");
            }
            current = current.getCause();
        }
        return false;
    }
}
