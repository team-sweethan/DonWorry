package com.donworry;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mariadb.MariaDBContainer;

@Tag("mariadb")
class AdminChangeActorMigrationMariaDbTests {
    private static final MariaDBContainer DATABASE = new MariaDBContainer("mariadb:11.8")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
    private JdbcTemplate jdbc;
    private long actorId;
    private long targetId;

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
                // 이 테스트만 소유한 일회용 DB다.
                .cleanDisabled(false)
                .load();
    }

    @BeforeEach
    void setUp() {
        var previous = flyway("20261103.174335");
        previous.clean();
        previous.migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        actorId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id='admin'", Long.class);
        targetId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id='boss'", Long.class);
    }

    private void insert(String type, Long actor, Long user, Long merchant) {
        jdbc.update(
                "INSERT INTO admin_change_histories(actor_type,actor_user_id,target_user_id,target_merchant_id,change_type,before_data,after_data,created_at) VALUES (?,?,?,?,'TEST','{\"status\":\"ACTIVE\"}','{\"status\":\"INACTIVE\"}',UTC_TIMESTAMP(6))",
                type,
                actor,
                user,
                merchant);
    }

    // 기존 감사 데이터는 STAFF 유형만 추가되고 기존 FK·대상 제약과 재실행 안전성을 유지한다
    @Test
    void migrate_whenAuditExists_backfillsStaffAndPreservesExistingData() {
        jdbc.update(
                "INSERT INTO admin_change_histories(actor_user_id,target_user_id,change_type,before_data,after_data,created_at) VALUES (?,?,'USER_INACTIVATED','{\"status\":\"ACTIVE\"}','{\"status\":\"INACTIVE\"}',UTC_TIMESTAMP(6))",
                actorId,
                targetId);
        var before = jdbc.queryForList("SELECT * FROM admin_change_histories ORDER BY history_id");
        before.forEach(row -> row.put("actor_type", "STAFF"));
        var latest = flyway("latest");
        latest.migrate();
        assertThat(jdbc.queryForList("SELECT * FROM admin_change_histories ORDER BY history_id"))
                .isEqualTo(before);
        insert("SYSTEM", null, targetId, null);
        insert("STAFF", actorId, targetId, null);
        // 기존 SQL 작성자도 DEFAULT로 STAFF를 저장할 수 있다.
        jdbc.update(
                "INSERT INTO admin_change_histories(actor_user_id,target_user_id,change_type,created_at) VALUES (?,?,'LEGACY',UTC_TIMESTAMP(6))",
                actorId,
                targetId);
        assertThat(jdbc.queryForObject(
                        "SELECT actor_type FROM admin_change_histories WHERE change_type='LEGACY'", String.class))
                .isEqualTo("STAFF");
        assertThatThrownBy(() -> insert("STAFF", Long.MAX_VALUE, targetId, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("SYSTEM", null, Long.MAX_VALUE, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("SYSTEM", null, null, Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("SYSTEM", null, targetId, 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("SYSTEM", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    // 수행자 유형과 ID의 잘못된 조합은 DB에서 거절한다
    @Test
    void actorConstraint_whenInvalidCombination_rejectsInsert() {
        flyway("latest").migrate();
        assertThatThrownBy(() -> insert("STAFF", null, targetId, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("SYSTEM", actorId, targetId, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String unknown : List.of("UNKNOWN", "")) {
            assertThatThrownBy(() -> insert(unknown, null, targetId, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insert(unknown, actorId, targetId, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insert(null, actorId, targetId, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
