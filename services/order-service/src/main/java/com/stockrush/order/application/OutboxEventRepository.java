// OutboxEventRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.order.application;

public interface OutboxEventRepository {

    void save(OutboxEventRecord<?> event);
}
