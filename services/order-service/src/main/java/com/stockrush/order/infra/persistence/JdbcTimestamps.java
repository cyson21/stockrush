// JdbcTimestamps: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

package com.stockrush.order.infra.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.SqlParameterValue;

final class JdbcTimestamps {

    private JdbcTimestamps() {
    }

    static SqlParameterValue timestampWithTimeZone(Instant instant) {
        return new SqlParameterValue(
            Types.TIMESTAMP_WITH_TIMEZONE,
            OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
        );
    }
}
