package com.donworry.settlement.repository;

import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementCalendarRepository {
    private final JdbcTemplate jdbc;

    public Optional<Boolean> findBusinessDay(LocalDate date) {
        return jdbc
                .query(
                        "SELECT business_day FROM settlement_business_days WHERE calendar_date = ?",
                        (rs, row) -> rs.getBoolean(1),
                        date)
                .stream()
                .findFirst();
    }
}
