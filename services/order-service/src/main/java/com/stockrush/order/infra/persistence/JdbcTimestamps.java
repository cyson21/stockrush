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
