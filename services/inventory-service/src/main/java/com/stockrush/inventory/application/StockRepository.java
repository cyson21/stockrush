// StockRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.inventory.application;

import java.util.List;
import java.util.Optional;

public interface StockRepository {

    List<StockSnapshot> findAll();

    List<StockSnapshot> findByProductCode(String productCode);

    Optional<StockSnapshot> findBySkuId(String skuId);

    StockSnapshot setAvailableQuantity(String skuId, String productCode, int availableQuantity);
}
