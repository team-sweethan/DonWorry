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
import org.springframework.transaction.TransactionDefinition;
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
    // 늦은 판정은 권장 격리(READ_COMMITTED)로 부른다 — 앞 판정을 기다린 뒤 이미 풀린 기록을 보고 아무것도 바꾸지 않는다
    @Test
    void release_concurrent_keepsFirstDecision() throws Exception {
        long adminId = admin();
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
            var second = executor.submit(() -> readCommitted()
                    .executeWithoutResult(tx -> locker.release(release(adminId, StaffLockOutcome.RESTORE, "CLEARED"))));
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

    // 해제가 커밋되기 전에 시작한 비밀번호 초기화는 해제를 기다리고, 해제 결과(상태)와 비밀번호가 서로를 덮어쓰지 않는다 (#649 리뷰)
    // 해제 쪽은 READ_COMMITTED 판정 트랜잭션, 초기화 쪽은 AdminAccountService와 같은 기본 격리 · 엔티티 저장으로 흉내 낸다
    @Test
    void release_whilePasswordResetPending_doesNotLoseEitherChange() throws Exception {
        long adminId = admin();
        locker.lockBySystem(staffId, StaffLockReason.INSIDER_EXPORT_BLOCKED, null);
        String oldHash = passwordHash();
        var released = new CountDownLatch(1);
        var commitRelease = new CountDownLatch(1);

        Throwable resetFailure;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var release = executor.submit(() -> readCommitted().executeWithoutResult(tx -> {
                locker.release(release(adminId, StaffLockOutcome.RESTORE, "CLEARED"));
                released.countDown();
                await(commitRelease);
            }));
            assertThat(released.await(10, TimeUnit.SECONDS)).isTrue();
            var reset = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                users.findById(staffId).orElseThrow().resetPasswordHash("reset-" + prefix);
            }));
            // 해제가 계정 행을 잡고 있어 초기화 저장은 기다린다
            assertThatThrownBy(() -> reset.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commitRelease.countDown();
            release.get(15, TimeUnit.SECONDS);
            resetFailure = catchThrowable(() -> reset.get(15, TimeUnit.SECONDS));
        } finally {
            commitRelease.countDown();
        }

        // 해제 결과는 남는다
        assertThat(status()).isEqualTo("ACTIVE");
        // 초기화는 해제 전에 읽은 SUSPENDED로 덮어쓰지 않고 거절된다 — MariaDB 11.8 스냅숏 격리("Record has changed since last read").
        // 관리자에게는 오류로 보이고 다시 누르면 된다
        assertThat(resetFailure).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(passwordHash()).isEqualTo(oldHash);
    }

    private long admin() {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,created_at,password_change_required) VALUES (?,'x','관리자','ACTIVE','ADMIN',UTC_TIMESTAMP(6),false)",
                prefix + "_admin");
        return jdbc.queryForObject(
                "SELECT user_id FROM platform_users WHERE login_id=?", Long.class, prefix + "_admin");
    }

    private TransactionTemplate readCommitted() {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private String passwordHash() {
        return jdbc.queryForObject("SELECT password_hash FROM platform_users WHERE user_id=?", String.class, staffId);
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
