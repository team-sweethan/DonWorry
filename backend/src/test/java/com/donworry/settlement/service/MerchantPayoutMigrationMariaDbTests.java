package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mariadb.MariaDBContainer;

@Tag("mariadb")
class MerchantPayoutMigrationMariaDbTests {
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
        var previous = flyway("20261103.133338");
        previous.clean();
        previous.migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
    }

    private void seed(long id, long merchant, long amount, String status, String completedAt) {
        jdbc.update(
                "INSERT INTO captures(capture_id,payment_id,merchant_id,order_id,capture_amount,status,captured_at,created_at,updated_at) VALUES (?,?,?,?,10000,'COMPLETED','2026-10-28 00:00:00',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                id,
                id,
                merchant,
                "P6-PAYOUT-MIG-" + id);
        jdbc.update(
                "INSERT INTO settlements(capture_id,payment_id,merchant_id,gross_amount,card_fee_rate,pg_fee_rate,card_fee_amount,pg_fee_amount,settlement_amount,status,completed_at,created_at,updated_at) VALUES (?,?,?,10000,0.02,0.01,200,100,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                id,
                id,
                merchant,
                amount,
                status,
                completedAt);
    }

    @Test
    void migrate_whenHistoryExists_preservesSourcesAndGroupsByKoreanDay() {
        seed(99401, 1, 9700, "COMPLETED", "2026-11-01 15:00:00.000001");
        seed(99402, 1, 19400, "COMPLETED", "2026-11-02 14:59:59.999999");
        seed(99403, 1, 9700, "COMPLETED", "2026-11-02 15:00:00");
        seed(99404, 2, 9700, "COMPLETED", "2026-11-01 15:00:00");
        seed(99405, 1, 9700, "SCHEDULED", null);
        seed(99406, 1, 9700, "CANCELED", null);
        var before = jdbc.queryForList("SELECT * FROM settlements ORDER BY settlement_id");
        var captures = jdbc.queryForList("SELECT * FROM captures ORDER BY capture_id");
        var latest = flyway("latest");
        latest.migrate();
        assertThat(jdbc.queryForList("SELECT * FROM settlements ORDER BY settlement_id"))
                .isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM captures ORDER BY capture_id"))
                .isEqualTo(captures);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_payouts", Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_payout_items", Long.class))
                .isEqualTo(4);
        var payout =
                jdbc.queryForMap("SELECT * FROM merchant_payouts WHERE merchant_id=1 AND payout_date='2026-11-02'");
        assertThat(((Number) payout.get("settlement_total")).longValue()).isEqualTo(29100);
        assertThat(((Number) payout.get("payout_amount")).longValue()).isEqualTo(29100);
        assertThat(payout.get("origin")).isEqualTo("LEGACY_BACKFILL");
        assertThat(jdbc.queryForObject(
                        "SELECT SUM(i.settlement_amount) FROM merchant_payout_items i WHERE i.payout_id=?",
                        Long.class,
                        payout.get("payout_id")))
                .isEqualTo(29100);
        var linked = jdbc.queryForList(
                "SELECT s.payment_id FROM merchant_payout_items i JOIN settlements s ON s.settlement_id=i.settlement_id ORDER BY s.payment_id",
                Long.class);
        assertThat(linked).containsExactly(99401L, 99402L, 99403L, 99404L);
        assertThatThrownBy(
                        () -> jdbc.update(
                                "INSERT INTO merchant_payout_items(payout_id,settlement_id,settlement_amount,created_at) SELECT payout_id,settlement_id,settlement_amount,created_at FROM merchant_payout_items LIMIT 1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    @Test
    void migrate_whenCompletionTimeMissing_rejectsBeforeCreatingPersistentLedger() {
        seed(99401, 1, 9700, "COMPLETED", null);
        rejectWithoutMutation();
    }

    @Test
    void migrate_whenHistoricalDailySumOverflows_rejectsBeforeCreatingPersistentLedger() {
        seed(99401, 1, Long.MAX_VALUE, "COMPLETED", "2026-11-01 15:00:00");
        seed(99402, 1, Long.MAX_VALUE, "COMPLETED", "2026-11-02 00:00:00");
        rejectWithoutMutation();
    }

    private void rejectWithoutMutation() {
        var before = jdbc.queryForList("SELECT * FROM settlements ORDER BY settlement_id");
        assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(jdbc.queryForList("SELECT * FROM settlements ORDER BY settlement_id"))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='merchant_payouts'",
                        Long.class))
                .isZero();
    }
}
