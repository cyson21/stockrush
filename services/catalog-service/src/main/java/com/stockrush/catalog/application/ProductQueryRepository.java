// ProductQueryRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.catalog.application;

import java.util.List;
import java.util.Optional;

public interface ProductQueryRepository {

    List<ProductSnapshot> findByStatus(String status, String query);

    Optional<ProductSnapshot> findByProductCode(String productCode);
}
