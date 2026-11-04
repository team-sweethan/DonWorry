package com.donworry;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mariadb.MariaDBContainer;

@Tag("mariadb")
class IdentityStatusMigrationMariaDbTests {
    private static final MariaDBContainer DATABASE = new MariaDBContainer("mariadb:11.8")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() {
        DATABASE.start();
    }

    @AfterAll
    static void stopDatabase() {
        DATABASE.stop();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                // 이 클래스가 소유한 일회용 컨테이너만 초기화한다.
                .cleanDisabled(false)
                .load();
    }

    @BeforeEach
    void preparePreviousSchema() {
        var previous = flyway("20261031.183416");
        previous.clean();
        previous.migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
    }

    private void insertApplication(String email, String businessNumber, String login, String status) {
        jdbc.update("""
                INSERT INTO merchant_applications
                (merchant_name,business_registration_number,contact_name,email,phone,requested_login_id,
                 consent_version,consented_at,status,created_at)
                VALUES ('상태 전환 테스트',?,'담당자',?,'01012345678',?,'v1',UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6))
                """, businessNumber, email, login, status);
    }

    private List<String> applicationStatements() throws Exception {
        var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*__rename_application_email_verification_status.sql");
        assertThat(resources).hasSize(1);
        String sql = resources[0].getContentAsString(StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        return Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void assertReservations(String status) {
        assertThatThrownBy(() -> insertApplication("pending@example.test", "9999999991", "other_id1", status))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertApplication("other1@example.test", "1234567890", "other_id2", status))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertApplication("other2@example.test", "9999999992", "reserved_id", status))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 기존 정지·진행 중 신청만 변환하며 시드·종료 신청·토큰·메일·감사 이력은 보존한다.
    @Test
    void migrate_whenPreviousDataExists_preservesDataAndReservations() {
        jdbc.update("UPDATE platform_users SET status='DISABLED' WHERE login_id='staff_test'");
        jdbc.update("""
                INSERT INTO admin_change_histories(actor_user_id,target_user_id,change_type,before_data,after_data,created_at)
                SELECT a.user_id,u.user_id,'USER_DISABLED','{"status":"ACTIVE"}','{"status":"DISABLED"}',UTC_TIMESTAMP(6)
                FROM platform_users a JOIN platform_users u ON u.login_id='staff_test' WHERE a.login_id='admin'
                """);
        insertApplication("pending@example.test", "1234567890", "reserved_id", "EMAIL_PENDING");
        insertApplication("review@example.test", "1234567891", "review_id", "REVIEW_PENDING");
        insertApplication("approved@example.test", "1234567892", "approved_id", "APPROVED");
        insertApplication("rejected@example.test", "1234567893", "rejected_id", "REJECTED");
        jdbc.update("""
                INSERT INTO application_tokens(application_id,purpose,token_hash,created_at,expires_at)
                SELECT application_id,'EMAIL_VERIFICATION',REPEAT('a',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 1 DAY
                FROM merchant_applications WHERE requested_login_id='reserved_id'
                """);
        jdbc.update("""
                INSERT INTO application_mail_deliveries(application_id,token_id,kind,status,started_at)
                SELECT application_id,token_id,'EMAIL_VERIFICATION','PENDING',UTC_TIMESTAMP(6) FROM application_tokens
                """);
        var accountsBefore =
                jdbc.queryForList("SELECT * FROM platform_users WHERE login_id<>'staff_test' ORDER BY user_id");
        var auditBefore = jdbc.queryForList("SELECT * FROM admin_change_histories ORDER BY history_id");
        // 후속 #517은 기존 감사 값은 보존하고 수행자 유형만 STAFF로 채운다.
        auditBefore.forEach(row -> row.put("actor_type", "STAFF"));
        var tokensBefore = jdbc.queryForList("SELECT * FROM application_tokens");
        var mailBefore = jdbc.queryForList("SELECT * FROM application_mail_deliveries");
        var pendingBefore =
                jdbc.queryForMap("SELECT * FROM merchant_applications WHERE requested_login_id='reserved_id'");
        var othersBefore = jdbc.queryForList(
                "SELECT * FROM merchant_applications WHERE requested_login_id<>'reserved_id' ORDER BY application_id");

        var latest = flyway("latest");
        latest.migrate();

        assertThat(jdbc.queryForObject("SELECT status FROM platform_users WHERE login_id='staff_test'", String.class))
                .isEqualTo("INACTIVE");
        assertThat(jdbc.queryForList("SELECT * FROM platform_users WHERE login_id<>'staff_test' ORDER BY user_id"))
                .isEqualTo(accountsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM admin_change_histories ORDER BY history_id"))
                .isEqualTo(auditBefore);
        assertThat(jdbc.queryForList("SELECT * FROM application_tokens")).isEqualTo(tokensBefore);
        assertThat(jdbc.queryForList("SELECT * FROM application_mail_deliveries"))
                .isEqualTo(mailBefore);
        pendingBefore.put("status", "EMAIL_VERIFICATION_PENDING");
        assertThat(jdbc.queryForMap("SELECT * FROM merchant_applications WHERE requested_login_id='reserved_id'"))
                .isEqualTo(pendingBefore);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM merchant_applications WHERE requested_login_id<>'reserved_id' ORDER BY application_id"))
                .isEqualTo(othersBefore);
        assertReservations("EMAIL_VERIFICATION_PENDING");
        assertReservations("REVIEW_PENDING");
        assertThatThrownBy(() -> insertApplication("old@example.test", "9999999993", "old_id", "EMAIL_PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    // DDL 각 단계와 데이터 변환 직후에도 두 이름의 신청이 같은 예약을 우회할 수 없다.
    @Test
    void migration_whenBetweenStatements_keepsAllThreeUniqueReservations() throws Exception {
        insertApplication("pending@example.test", "1234567890", "reserved_id", "EMAIL_PENDING");
        var statements = applicationStatements();
        assertThat(statements).hasSize(3);
        jdbc.execute(statements.get(0));
        assertReservations("EMAIL_PENDING");
        assertReservations("EMAIL_VERIFICATION_PENDING");
        jdbc.execute(statements.get(1));
        assertReservations("EMAIL_PENDING");
        assertReservations("EMAIL_VERIFICATION_PENDING");
        jdbc.execute(statements.get(2));
        assertReservations("EMAIL_VERIFICATION_PENDING");
        jdbc.update("UPDATE merchant_applications SET status='REVIEW_PENDING'");
        assertReservations("EMAIL_VERIFICATION_PENDING");
        jdbc.update("UPDATE merchant_applications SET status='REJECTED'");
        insertApplication("pending@example.test", "1234567890", "reserved_id", "EMAIL_VERIFICATION_PENDING");
        assertReservations("REVIEW_PENDING");
    }
}
