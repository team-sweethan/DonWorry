package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.settlement.repository.SettlementCalendarRepository;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mariadb.MariaDBContainer;

@Tag("mariadb")
class CaptureSettlementMigrationMariaDbTests {
    private static final MariaDBContainer DATABASE = new MariaDBContainer("mariadb:11.8")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
    private JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        DATABASE.start();
    }

    @AfterAll
    static void stop() {
        DATABASE.stop();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    @BeforeEach
    void previous() {
        var previous = flyway("20261102.142818");
        previous.clean();
        previous.migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
    }

    private void seed(long id, String capturedAt, String status) {
        jdbc.update(
                "INSERT INTO captures(capture_id,payment_id,merchant_id,order_id,capture_amount,status,captured_at,created_at,updated_at) VALUES (?,?,1,?,10000,'COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                id,
                id,
                "P6-D3-" + id,
                capturedAt);
        jdbc.update(
                "INSERT INTO settlements(capture_id,payment_id,merchant_id,gross_amount,card_fee_rate,pg_fee_rate,card_fee_amount,pg_fee_amount,settlement_amount,status,expected_settlement_at,scheduled_at,completed_at,created_at,updated_at) VALUES (?,?,1,10000,0.02,0.01,200,100,9700,?,'2026-11-10 00:00:00','2026-11-11 00:00:00','2026-01-01 01:00:00',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                id,
                id,
                status);
    }

    @Test
    void migrate_whenOldSchedulesExist_convertsOnlyUnpaidAndMatchesJava() {
        seed(99101, "2026-10-28 00:00:00", "SCHEDULED");
        seed(99102, "2026-10-28 14:59:59.999999", "SCHEDULED");
        seed(99103, "2025-12-01 00:00:00", "COMPLETED");
        seed(99104, "2025-12-01 00:00:00", "CANCELED");
        var historical =
                jdbc.queryForList("SELECT * FROM settlements WHERE payment_id IN (99103,99104) ORDER BY payment_id");
        var latest = flyway("latest");
        latest.migrate();
        assertThat(jdbc.queryForList("SELECT * FROM settlements WHERE payment_id IN (99103,99104) ORDER BY payment_id"))
                .isEqualTo(historical);
        var schedule = new CaptureSettlementSchedule(new SettlementCalendarRepository(jdbc));
        assertThat(schedule.settlementAt(Instant.parse("2026-10-28T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-01T15:00:00Z"));
        assertThat(jdbc.queryForList(
                        "SELECT scheduled_at FROM settlements WHERE payment_id IN (99101,99102)",
                        java.time.LocalDateTime.class))
                .containsOnly(java.time.LocalDateTime.of(2026, 11, 1, 15, 0));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlements WHERE scheduled_at=expected_settlement_at AND payment_id IN (99101,99102)",
                        Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_business_days", Long.class))
                .isEqualTo(730);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlement_business_days WHERE calendar_date BETWEEN '2026-01-01' AND '2026-12-31' AND business_day=0",
                        Long.class))
                .isEqualTo(120);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlement_business_days WHERE calendar_date BETWEEN '2027-01-01' AND '2027-12-31' AND business_day=0",
                        Long.class))
                .isEqualTo(119);
        assertThat(schedule.settlementAt(Instant.parse("2027-05-12T00:00:00Z")))
                .isEqualTo(Instant.parse("2027-05-17T15:00:00Z"));
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    @Test
    void migrate_whenCalendarCannotCoverThirdDay_preservesOldScheduleAndFails() {
        seed(99101, "2027-12-31 00:00:00", "SCHEDULED");
        var before = jdbc.queryForList("SELECT * FROM settlements WHERE payment_id=99101");
        assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(jdbc.queryForList("SELECT * FROM settlements WHERE payment_id=99101"))
                .isEqualTo(before);
    }
}
