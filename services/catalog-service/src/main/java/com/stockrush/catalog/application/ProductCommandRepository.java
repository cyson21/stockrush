// ProductCommandRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.catalog.application;

import java.math.BigDecimal;
import java.util.Optional;

public interface ProductCommandRepository {

    ProductSnapshot create(ProductSnapshot product);

    Optional<ProductSnapshot> update(String productCode, String name, String salesStatus, BigDecimal listPrice);
}
