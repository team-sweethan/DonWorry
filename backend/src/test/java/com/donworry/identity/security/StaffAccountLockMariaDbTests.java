package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockOutcome;
import com.donworry.identity.api.StaffLockReason;
import com.donworry.identity.api.StaffLockRelease;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.service.UserSessionService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StaffAccountLockMariaDbTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    StaffAccountLocker locker;

    @Autowired
    SessionRegistry registry;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    PlatformUserRepository users;

    @Autowired
    UserSessionService sessions;

    private String prefix;
    private String staffLogin;
    private long staffId;

    @BeforeEach
    void setUp() {
        prefix = "s647_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        staffLogin = prefix + "_staff";
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,created_at,password_change_required) VALUES (?,?,'정지 테스트','ACTIVE','SECURITY_STAFF',UTC_TIMESTAMP(6),false)",
                staffLogin,
                encoder.encode("StaffPass123"));
        staffId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, staffLogin);
    }

    @AfterEach
    void cleanUp() {
        sessions.expireAll(staffId);
        jdbc.update("DELETE FROM staff_account_locks WHERE staff_id=?", staffId);
        jdbc.update("DELETE FROM login_histories WHERE user_id=?", staffId);
        jdbc.update("DELETE FROM admin_change_histories WHERE target_user_id=?", staffId);
        jdbc.update("DELETE FROM platform_users WHERE user_id=?", staffId);
        jdbc.update(
                "DELETE FROM admin_change_histories WHERE actor_user_id IN (SELECT user_id FROM platform_users WHERE login_id LIKE ?)",
                prefix + "%");
        jdbc.update("DELETE FROM platform_users WHERE login_id LIKE ?", prefix + "%");
    }

    // 보안 정지(SUSPENDED)된 직원은 맞는 비밀번호로도 로그인할 수 없다 — "사용 중만 통과" 검사가 새 상태에도 걸린다
    @Test
    void login_suspendedAccount_isRejected() throws Exception {
        assertThat(login().statusCode()).isEqualTo(200);
        locker.lockBySystem(staffId, StaffLockReason.INSIDER_EXPORT_BLOCKED, null);
        assertThat(status()).isEqualTo("SUSPENDED");

        var denied = login();

        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(mapper.readTree(denied.body()).path("code").asString()).isEqualTo("AUTH_004");
        // 정지 이유를 로그인 화면에 드러내지 않는다
        assertThat(denied.body()).doesNotContain("SUSPENDED", "INSIDER");
    }

    // 정지를 부른 쪽(내보내기 차단 응답)이 롤백돼도 이미 커밋된 정지 · 기록 · 세션 만료는 남는다
    @Test
    void lock_survivesCallerRollback() {
        registry.registerNewSession(prefix, staffId);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var previous = users.findById(staffId).orElseThrow();
            assertThat(previous.getStatus()).isEqualTo(UserStatus.ACTIVE);
            locker.lockBySystem(staffId, StaffLockReason.INSIDER_EXPORT_BLOCKED, 99L);
            tx.setRollbackOnly();
        });

        assertThat(status()).isEqualTo("SUSPENDED");
        var lock = jdbc.queryForMap("SELECT * FROM staff_account_locks WHERE staff_id=?", staffId);
        assertThat(lock.get("status")).isEqualTo("OPEN");
        assertThat(lock.get("locked_by_type")).isEqualTo("SYSTEM");
        assertThat(lock.get("locked_by")).isNull();
        assertThat(((Number) lock.get("source_alert_id")).longValue()).isEqualTo(99L);
        assertThat(jdbc.queryForObject(
                        "SELECT actor_type FROM admin_change_histories WHERE target_user_id=? AND change_type='USER_SUSPENDED_BY_INSIDER'",
                        String.class,
                        staffId))
                .isEqualTo("SYSTEM");
        assertThat(registry.getSessionInformation(prefix).isExpired()).isTrue();
    }

    // 같은 정지를 동시에 풀어도 먼저 커밋된 판정이 남고, 늦은 판정이 해제 결과 · 계정 상태를 덮어쓰지 않는다 (#649 리뷰)
    @Test
    void release_concurrent_keepsFirstDecision() throws Exception {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,created_at,password_change_required) VALUES (?,'x','관리자','ACTIVE','ADMIN',UTC_TIMESTAMP(6),false)",
                prefix + "_admin");
        long adminId = jdbc.queryForObject(
                "SELECT user_id FROM platform_users WHERE login_id=?", Long.class, prefix + "_admin");
        locker.lockBySystem(staffId, StaffLockReason.INSIDER_EXPORT_BLOCKED, null);
        var firstReleased = new CountDownLatch(1);
        var commitFirst = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // 앞 판정: 징계(비활성)로 풀고, 커밋하기 전에 멈춘다
            var first = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                locker.release(release(adminId, StaffLockOutcome.DEACTIVATE, "DISCIPLINE"));
                firstReleased.countDown();
                await(commitFirst);
            }));
            assertThat(firstReleased.await(10, TimeUnit.SECONDS)).isTrue();
            // 늦은 판정: 무혐의(복귀) — 앞 판정이 끝날 때까지 기다려야 한다
            var second = executor.submit(() -> locker.release(release(adminId, StaffLockOutcome.RESTORE, "CLEARED")));
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commitFirst.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            commitFirst.countDown();
        }

        assertThat(status()).isEqualTo("INACTIVE");
        var lock = jdbc.queryForMap("SELECT * FROM staff_account_locks WHERE staff_id=?", staffId);
        assertThat(lock.get("status")).isEqualTo("RELEASED");
        assertThat(lock.get("release_outcome")).isEqualTo("DEACTIVATE");
        assertThat(lock.get("release_decision")).isEqualTo("DISCIPLINE");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM admin_change_histories WHERE target_user_id=? AND change_type='USER_RELEASED_BY_INSIDER_DECISION'",
                        Integer.class,
                        staffId))
                .isEqualTo(1);
    }

    private StaffLockRelease release(long adminId, StaffLockOutcome outcome, String decision) {
        return StaffLockRelease.builder()
                .staffId(staffId)
                .adminId(adminId)
                .caseId(1L)
                .decision(decision)
                .outcome(outcome)
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM platform_users WHERE user_id=?", String.class, staffId);
    }

    private HttpResponse<String> login() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        return com.donworry.HttpCsrfTestSupport.send(
                client,
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .method(
                                "POST",
                                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                                        Map.of("loginId", staffLogin, "password", "StaffPass123")))));
    }
}
