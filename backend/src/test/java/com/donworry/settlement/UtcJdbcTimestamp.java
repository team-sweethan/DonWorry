package com.donworry.settlement;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.jdbc.core.SqlTypeValue;

// Direct JDBC fixtures do not inherit Hibernate's jdbc.time_zone setting.
public final class UtcJdbcTimestamp {
    private UtcJdbcTimestamp() {}

    public static SqlTypeValue utcTimestamp(Instant instant) {
        return (statement, index, sqlType, typeName) -> statement.setTimestamp(
                index, Timestamp.from(instant), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
    }
}
