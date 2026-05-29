package com.stockrush.fulfillment.infra.persistence;

import com.stockrush.fulfillment.application.FulfillmentRequestQueryRepository;
import com.stockrush.fulfillment.application.FulfillmentRequestSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
/**
 * JDBC 또는 JPA를 통해 영속 저장소를 직접 조회/갱신하며, 도메인 규칙 위임용 데이터 경계 역할을 합니다.
 */


@Repository
class JdbcFulfillmentRequestQueryRepository implements FulfillmentRequestQueryRepository {

    private final JdbcClient jdbcClient;

    JdbcFulfillmentRequestQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<FulfillmentRequestSnapshot> list(String orderId, String status, int limit, int offset) {
        return jdbcClient.sql("""
                select request_id, order_id, status, requested_at, source_event_id,
                       correlation_id, idempotency_key, created_at, updated_at
                  from fulfillment_requests
                 where (:orderIdFilter = false or order_id = :orderId)
                   and (:statusFilter = false or status = :status)
                 order by updated_at desc, id desc
                 limit :limit
                 offset :offset
                """)
            .param("orderIdFilter", orderId != null)
            .param("orderId", orderId == null ? "" : orderId)
            .param("statusFilter", status != null)
            .param("status", status == null ? "" : status)
            .param("limit", limit)
            .param("offset", offset)
            .query(this::map)
            .list();
    }

    private FulfillmentRequestSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new FulfillmentRequestSnapshot(
            rs.getObject("request_id", UUID.class),
            rs.getString("order_id"),
            rs.getString("status"),
            rs.getObject("requested_at", OffsetDateTime.class),
            rs.getObject("source_event_id", UUID.class),
            rs.getString("correlation_id"),
            rs.getString("idempotency_key"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
