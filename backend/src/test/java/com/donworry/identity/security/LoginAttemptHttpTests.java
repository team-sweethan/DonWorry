package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.service.LoginAttemptService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
// 전용 Clock·Spy 컨텍스트의 커넥션 풀이 뒤의 다른 모듈 테스트까지 남지 않게 한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginAttemptHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @MockitoSpyBean
    LoginAttemptService attempts;

    @Autowired
    PlatformTransactionManager transactions;

    @MockitoSpyBean
    PlatformUserRepository repository;

    @MockitoBean(name = "identityClock")
    Clock clock;

    private final Instant start = Instant.parse("2026-10-25T03:00:00.123456Z");
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private final String password = "Original123!";
    private String loginId;
    private Long userId;

    @BeforeEach
    void setUp() {
        time.set(start);
        given(clock.instant()).willAnswer(invocation -> time.get());
        loginId = "login_" + UUID.randomUUID().toString().replace("-", "");
        userId = createAccount(loginId);
    }

    private Long createAccount(String id) {
        jdbc.update("""
                INSERT INTO platform_users (login_id, password_hash, display_name, status, role, created_at)
                VALUES (?, ?, '로그인 제한 테스트', 'ACTIVE', 'ADMIN', UTC_TIMESTAMP(6))
                """, id, encoder.encode(password));
        return jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id = ?", Long.class, id);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, Map<String, ?> body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(HttpClient client, String id, String value) throws Exception {
        return send(client, "POST", "/api/auth/login", Map.of("loginId", id, "password", value));
    }

    private void fail(int times) throws Exception {
        var client = client();
        for (int i = 0; i < times; i++) assertProblem(login(client, loginId, "wrong"), 401, "AUTH_004");
    }

    private int failures() {
        return jdbc.queryForObject(
                "SELECT login_failure_count FROM platform_users WHERE user_id = ?", Integer.class, userId);
    }

    private LocalDateTime lockedUntil() {
        return jdbc.queryForObject(
                "SELECT login_locked_until FROM platform_users WHERE user_id = ?", LocalDateTime.class, userId);
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("application/problem+json");
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.body())
                .doesNotContain(password, "loginFailureCount", "loginLockedUntil", "password_hash", "$2a$");
        assertThat(response.headers().allValues("set-cookie")).noneMatch(value -> value.startsWith("JSESSIONID="));
    }

    // 세 역할 모두 4회 오류 뒤 성공하면 DB 실패 횟수가 0으로 초기화된다
    @ParameterizedTest
    @EnumSource(UserRole.class)
    void login_whenSuccessfulAfterFourFailures_resetsCounterForEveryRole(UserRole role) throws Exception {
        Long merchantId = role == UserRole.OPERATOR
                ? jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code = 'DEMOSHOP'", Long.class)
                : null;
        jdbc.update(
                "UPDATE platform_users SET role = ?, merchant_id = ? WHERE user_id = ?",
                role.name(),
                merchantId,
                userId);
        assertThat(failures()).isZero();
        assertThat(lockedUntil()).isNull();
        fail(4);
        assertThat(failures()).isEqualTo(4);
        var loggedIn = client();
        var response = login(loggedIn, loginId, password);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("loginFailureCount", "loginLockedUntil");
        assertThat(failures()).isZero();
        assertThat(lockedUntil()).isNull();
        assertThat(send(loggedIn, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        fail(1);
        assertThat(failures()).isEqualTo(1);
    }

    // 잠금 중 오답과 정답 재시도는 종료 시각을 연장하지 않고 기존 로그인도 끊지 않는다
    @Test
    void login_whenLocked_preservesDeadlineAndExistingSession() throws Exception {
        var existing = client();
        assertThat(login(existing, loginId, password).statusCode()).isEqualTo(200);
        fail(5);
        var until = LocalDateTime.ofInstant(start.plusSeconds(900), ZoneOffset.UTC);
        assertThat(failures()).isEqualTo(5);
        assertThat(lockedUntil()).isEqualTo(until);
        time.set(start.plusSeconds(300));
        assertProblem(login(client(), loginId, password), 401, "AUTH_004");
        fail(2);
        assertThat(failures()).isEqualTo(5);
        assertThat(lockedUntil()).isEqualTo(until);
        assertThat(send(existing, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        var entity = repository.findById(userId).orElseThrow();
        assertThat(entity.getLoginFailureCount()).isEqualTo(5);
        assertThat(entity.getLoginLockedUntil()).isEqualTo(until);
    }

    // 실제 대기 없이 UTC 만료 1마이크로초 전과 정확한 만료 시각을 HTTP로 검증한다
    @Test
    void login_whenAtExpiryBoundary_allowsCorrectPasswordOnlyAtDeadline() throws Exception {
        fail(5);
        time.set(start.plusSeconds(900).minusNanos(1000));
        assertProblem(login(client(), loginId, password), 401, "AUTH_004");
        time.set(start.plusSeconds(900));
        assertThat(login(client(), loginId, password).statusCode()).isEqualTo(200);
        assertThat(failures()).isZero();
        assertThat(lockedUntil()).isNull();
    }

    // 만료 후 첫 오답은 1회이며 다섯 번째 오답부터 새로운 15분을 적용한다
    @Test
    void login_whenWrongAfterExpiry_startsNewFailureSequence() throws Exception {
        fail(5);
        time.set(start.plusSeconds(901));
        fail(1);
        assertThat(failures()).isEqualTo(1);
        assertThat(lockedUntil()).isNull();
        fail(3);
        assertThat(failures()).isEqualTo(4);
        assertThat(lockedUntil()).isNull();
        fail(1);
        assertThat(failures()).isEqualTo(5);
        assertThat(lockedUntil()).isEqualTo(LocalDateTime.ofInstant(time.get().plusSeconds(900), ZoneOffset.UTC));
    }

    // 없는 계정·비밀번호 오류·계정 정지·잠금의 오류 응답은 같고 정지는 횟수를 바꾸지 않는다
    @Test
    void login_whenRejected_returnsIdenticalProblemsWithoutCountingDisabledAccounts() throws Exception {
        var missing = login(client(), loginId + "_missing", password);
        var wrong = login(client(), loginId, "wrong");
        assertProblem(missing, 401, "AUTH_004");
        assertThat(wrong.body()).isEqualTo(missing.body());
        assertThat(failures()).isEqualTo(1);
        jdbc.update("UPDATE platform_users SET status = 'DISABLED' WHERE user_id = ?", userId);
        assertThat(login(client(), loginId, password).body()).isEqualTo(missing.body());
        fail(5);
        assertThat(failures()).isEqualTo(1);
        jdbc.update("UPDATE platform_users SET status = 'ACTIVE' WHERE user_id = ?", userId);
        fail(4);
        assertThat(login(client(), loginId, password).body()).isEqualTo(missing.body());
        assertThat(failures()).isEqualTo(5);
    }

    // 잘못된 요청과 계정·가맹점 상태에 의한 거절은 비밀번호 오류로 세지 않는다
    @Test
    void login_whenValidationOrMerchantStatusRejects_doesNotCountFailure() throws Exception {
        assertThat(send(client(), "POST", "/api/auth/login", Map.of("loginId", loginId, "password", " "))
                        .statusCode())
                .isEqualTo(400);
        jdbc.update("""
                INSERT INTO merchants (merchant_code, display_name, status, pg_fee_rate, created_at)
                VALUES (?, '정지 가맹점', 'INACTIVE', 0.01, UTC_TIMESTAMP(6))
                """, loginId.toUpperCase(java.util.Locale.ROOT));
        var merchantId = jdbc.queryForObject(
                "SELECT merchant_id FROM merchants WHERE merchant_code = ?",
                Long.class,
                loginId.toUpperCase(java.util.Locale.ROOT));
        jdbc.update(
                "UPDATE platform_users SET role = 'OPERATOR', merchant_id = ? WHERE user_id = ?", merchantId, userId);
        assertProblem(login(client(), loginId, password), 401, "AUTH_004");
        assertThat(failures()).isZero();
        jdbc.update("UPDATE merchants SET status = 'ACTIVE' WHERE merchant_id = ?", merchantId);
        fail(5);
        jdbc.update("UPDATE merchants SET status = 'INACTIVE' WHERE merchant_id = ?", merchantId);
        time.set(start.plusSeconds(900));
        assertProblem(login(client(), loginId, password), 401, "AUTH_004");
    }

    // 아이디 정규화와 DB 악센트 별칭은 같은 계정의 실패 횟수를 공유하며 다른 계정은 독립이다
    @Test
    void login_whenUsingAliases_sharesOnlyTheActualAccountCounter() throws Exception {
        var otherId = loginId + "_other";
        var otherUser = createAccount(otherId);
        var alias = loginId.replace("login_", "lógin_");
        for (String id :
                new String[] {alias, loginId, alias, "  " + loginId.toUpperCase(java.util.Locale.ROOT) + "  ", loginId
                }) {
            assertProblem(login(client(), id, "wrong"), 401, "AUTH_004");
        }
        assertThat(failures()).isEqualTo(5);
        assertProblem(login(client(), alias, password), 401, "AUTH_004");
        assertThat(login(client(), otherId, password).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT login_failure_count FROM platform_users WHERE user_id = ?", Integer.class, otherUser))
                .isZero();
    }

    // JVM 계정 잠금 없이 동시에 UPDATE해도 증가가 유실되지 않고 잠금 이후는 갱신하지 않는다
    @Test
    void recordFailure_whenConcurrentDatabaseUpdates_keepsEveryIncrementAndFixedDeadline() throws Exception {
        runConcurrently(4, () -> attempts.recordFailure(loginId));
        assertThat(failures()).isEqualTo(4);
        assertThat(lockedUntil()).isNull();
        runConcurrently(4, () -> attempts.recordFailure(loginId));
        assertThat(failures()).isEqualTo(5);
        assertThat(lockedUntil()).isEqualTo(LocalDateTime.ofInstant(start.plusSeconds(900), ZoneOffset.UTC));
    }

    // 동시에 들어온 HTTP 오답도 모두 401이며 최종 상태는 정확히 5회 잠금이다
    @Test
    void login_whenConcurrentWrongPasswords_locksAfterFiveFailures() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var startGate = new CountDownLatch(1);
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 7; i++)
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return login(client(), loginId, "wrong");
                }));
            startGate.countDown();
            for (var future : futures) assertProblem(future.get(20, TimeUnit.SECONDS), 401, "AUTH_004");
        }
        assertThat(failures()).isEqualTo(5);
        assertThat(lockedUntil()).isEqualTo(LocalDateTime.ofInstant(start.plusSeconds(900), ZoneOffset.UTC));
    }

    // 다섯 번째 실패와 성공이 겹쳐도 먼저 들어온 요청의 커밋 뒤 상태로 다음 인증을 판단한다
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void login_whenSuccessRacesWithFifthFailure_observesCommittedOrder(boolean failureFirst) throws Exception {
        fail(4);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocking = doAnswer(invocation -> {
            reached.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        });
        if (failureFirst) blocking.when(attempts).recordFailure(loginId);
        else blocking.when(attempts).clearFailures(userId);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> login(client(), loginId, failureFirst ? "wrong" : password));
            try {
                assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> login(client(), loginId, failureFirst ? password : "wrong"));
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(failureFirst ? 401 : 200);
                assertProblem(second.get(10, TimeUnit.SECONDS), 401, "AUTH_004");
                assertThat(failures()).isEqualTo(failureFirst ? 5 : 1);
                if (failureFirst)
                    assertThat(lockedUntil())
                            .isEqualTo(LocalDateTime.ofInstant(start.plusSeconds(900), ZoneOffset.UTC));
                else assertThat(lockedUntil()).isNull();
            } finally {
                release.countDown();
            }
        }
    }

    private void runConcurrently(int count, Runnable operation) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < count; i++)
                futures.add(executor.submit(() -> {
                    gate.await();
                    operation.run();
                    return null;
                }));
            gate.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
    }

    // 바깥 트랜잭션이 실패해도 로그인 실패 횟수는 독립 커밋으로 남는다
    @Test
    void recordFailure_whenOuterTransactionRollsBack_preservesCommittedFailure() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    attempts.recordFailure(loginId);
                    throw new IllegalStateException("outer request failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(failures()).isEqualTo(1);
    }

    // 실패 기록이 없는 계정은 성공 초기화 UPDATE가 행을 바꾸지 않고, 기록이 있으면 0·NULL로 되돌린다
    @Test
    void clearLoginFailures_whenNoFailureRecorded_updatesNoRow() {
        var transaction = new TransactionTemplate(transactions);
        assertThat(transaction.<Integer>execute(status -> repository.clearLoginFailures(userId)))
                .isZero();
        attempts.recordFailure(loginId);
        assertThat(transaction.<Integer>execute(status -> repository.clearLoginFailures(userId)))
                .isEqualTo(1);
        assertThat(failures()).isZero();
        assertThat(lockedUntil()).isNull();
    }

    // 증가·초기화 UPDATE 오류는 AUTH_005이며 기존 횟수와 인증되지 않은 상태를 유지한다
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void login_whenPersistenceFails_returnsServerErrorWithoutChangingCounter(boolean wrongPassword) throws Exception {
        fail(4);
        var failure = new DataAccessResourceFailureException("attempt db down");
        if (wrongPassword) doThrow(failure).when(repository).recordLoginFailure(anyString(), any(), any());
        else doThrow(failure).when(repository).clearLoginFailures(anyLong());
        var browser = client();
        assertProblem(login(browser, loginId, wrongPassword ? "wrong" : password), 500, "AUTH_005");
        assertThat(failures()).isEqualTo(4);
        assertThat(lockedUntil()).isNull();
        assertThat(send(browser, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
    }

    // 현재 세션에서 비밀번호를 바꿔도 다른 기기에서 유발한 로그인 잠금은 유지한다
    @Test
    void passwordChange_whenLoginLocked_preservesLockAndCurrentSession() throws Exception {
        var existing = client();
        assertThat(login(existing, loginId, password).statusCode()).isEqualTo(200);
        fail(5);
        var until = lockedUntil();
        var next = "Changed123!";
        assertThat(send(
                                existing,
                                "POST",
                                "/api/auth/password",
                                Map.of("currentPassword", password, "newPassword", next))
                        .statusCode())
                .isEqualTo(204);
        assertThat(failures()).isEqualTo(5);
        assertThat(lockedUntil()).isEqualTo(until);
        assertThat(send(existing, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        assertProblem(login(client(), loginId, next), 401, "AUTH_004");
        time.set(start.plusSeconds(900));
        assertThat(login(client(), loginId, next).statusCode()).isEqualTo(200);
    }

    // 관리자 초기화와 계정 재활성화는 비밀번호·상태만 바꾸며 로그인 잠금을 해제하지 않는다
    @Test
    void adminResetAndReactivation_whenLoginLocked_preserveLock() throws Exception {
        var managerId = loginId + "_manager";
        createAccount(managerId);
        var manager = client();
        assertThat(login(manager, managerId, password).statusCode()).isEqualTo(200);
        fail(5);
        var until = lockedUntil();
        var reset = send(manager, "POST", "/api/admin/users/" + userId + "/password-reset", Map.of());
        assertThat(reset.statusCode()).isEqualTo(200);
        var temporary = mapper.readTree(reset.body()).path("temporaryPassword").asString();
        assertThat(temporary).isNotBlank();
        assertProblem(login(client(), loginId, temporary), 401, "AUTH_004");
        for (String state : new String[] {"DISABLED", "ACTIVE"}) {
            assertThat(send(manager, "PUT", "/api/admin/users/" + userId + "/status", Map.of("status", state))
                            .statusCode())
                    .isEqualTo(204);
        }
        assertThat(failures()).isEqualTo(5);
        assertThat(lockedUntil()).isEqualTo(until);
        assertProblem(login(client(), loginId, temporary), 401, "AUTH_004");
        time.set(start.plusSeconds(900));
        var response = login(client(), loginId, temporary);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body())
                        .path("passwordChangeRequired")
                        .asBoolean())
                .isTrue();
    }
}
