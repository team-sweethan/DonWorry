package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.LoginHistoryRepository;
import com.donworry.identity.service.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class LoginHistoryHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    LoginHistoryService histories;

    @Autowired
    LoginHistoryRetentionService retention;

    @Autowired
    LoginHistoryQueryService queries;

    @Autowired
    PlatformTransactionManager transactions;

    @MockitoSpyBean
    LoginHistoryRepository repository;

    @MockitoSpyBean(name = "securityContextRepository")
    org.springframework.security.web.context.SecurityContextRepository contexts;

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
        given(clock.instant()).willAnswer(i -> time.get());
        loginId = "history_" + UUID.randomUUID().toString().replace("-", "");
        userId = account(loginId);
    }

    private Long account(String id) {
        jdbc.update(
                "INSERT INTO platform_users (login_id, password_hash, display_name, status, role, created_at) VALUES (?, ?, '이력 테스트', 'ACTIVE', 'ADMIN', UTC_TIMESTAMP(6))",
                id,
                encoder.encode(password));
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
                        .header("User-Agent", "HistoryTest Chrome/120 Windows")
                        .header("X-Forwarded-For", "203.0.113.99")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(HttpClient c, String id, String pass) throws Exception {
        return send(c, "POST", "/api/auth/login", Map.of("loginId", id, "password", pass));
    }

    private JsonNode get(HttpClient c, String path) throws Exception {
        var response = send(c, "GET", path, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
        return mapper.readTree(response.body());
    }

    private int count(String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_histories WHERE user_id = ? AND event_type = ?",
                Integer.class,
                userId,
                type);
    }

    private SessionUser user() {
        return SessionUser.builder()
                .userId(userId)
                .loginId(loginId)
                .role(UserRole.ADMIN)
                .permissions(List.of())
                .loginReference(UUID.randomUUID())
                .build();
    }

    private void seed(Long id, String type, Instant at) {
        jdbc.update(
                "INSERT INTO login_histories(user_id, event_type, occurred_at, login_reference) VALUES (?, ?, ?, ?)",
                id,
                type,
                LocalDateTime.ofInstant(at, ZoneOffset.UTC),
                type.equals("LOGIN_SUCCESS") ? UUID.randomUUID().toString() : null);
    }

    // 성공·오답을 실제 HTTP로 기록하고 IP·UTC 시각·조회 응답에서 인증 비밀을 제외한다
    @Test
    void login_whenSuccessAndWrongPassword_recordsHistoryAndLatest() throws Exception {
        assertThat(login(client(), loginId, "wrong").statusCode()).isEqualTo(401);
        var browser = client();
        var response = login(browser, loginId, password);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("loginReference");
        var page = get(browser, "/api/auth/login-histories?userId=999");
        assertThat(page.path("totalElements").asInt()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("eventType").asString()).isEqualTo("LOGIN_SUCCESS");
        assertThat(page.toString())
                .contains(start.toString())
                .doesNotContain(password, "loginReference", "203.0.113.99");
        assertThat(page.path("items").get(0).path("clientIp").asString()).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
        assertThat(get(browser, "/api/auth/login-histories/latest-success")
                        .path("latestSuccess")
                        .path("occurredAt")
                        .asString())
                .isEqualTo(start.toString());
    }

    // 없는 아이디 실패는 NULL 계정이며 나중에 같은 아이디를 생성해도 연결하지 않는다
    @Test
    void failure_whenUnknownAccount_neverRelinksAfterCreation() throws Exception {
        var missing = loginId + "_new";
        var browser = client();
        assertThat(login(browser, missing, password).statusCode()).isEqualTo(401);
        Long created = account(missing);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM login_histories WHERE user_id = ?", Integer.class, created))
                .isZero();
        assertThat(login(browser, missing, password).statusCode()).isEqualTo(200);
        assertThat(get(browser, "/api/auth/login-histories")
                        .path("totalElements")
                        .asInt())
                .isEqualTo(1);
        assertThat(get(browser, "/api/admin/users/" + created + "/login-histories")
                        .path("totalElements")
                        .asInt())
                .isEqualTo(1);
    }

    // 정지·잠금·가맹점 정지의 거절도 기록하지만 입력 400과 서버 장애는 실패로 기록하지 않는다
    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "LOCKED", "MERCHANT"})
    void failure_whenStatusRejects_recordsWithoutChangingLockState(String condition) throws Exception {
        if (condition.equals("DISABLED"))
            jdbc.update("UPDATE platform_users SET status = 'DISABLED' WHERE user_id = ?", userId);
        if (condition.equals("LOCKED"))
            jdbc.update(
                    "UPDATE platform_users SET login_failure_count=5, login_locked_until=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE) WHERE user_id=?",
                    userId);
        if (condition.equals("MERCHANT")) {
            jdbc.update(
                    "INSERT INTO merchants(merchant_code, display_name, status, pg_fee_rate, created_at) VALUES (?, '정지', 'INACTIVE', 0.01, UTC_TIMESTAMP(6))",
                    loginId);
            Long merchant =
                    jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, loginId);
            jdbc.update("UPDATE platform_users SET role='OPERATOR', merchant_id=? WHERE user_id=?", merchant, userId);
        }
        var before = jdbc.queryForMap(
                "SELECT login_failure_count, login_locked_until FROM platform_users WHERE user_id=?", userId);
        assertThat(login(client(), loginId, password).statusCode()).isEqualTo(401);
        assertThat(count("LOGIN_FAILURE")).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "SELECT login_failure_count, login_locked_until FROM platform_users WHERE user_id=?", userId))
                .isEqualTo(before);
        assertThat(send(client(), "POST", "/api/auth/login", Map.of("loginId", loginId, "password", " "))
                        .statusCode())
                .isEqualTo(400);
        assertThat(count("LOGIN_FAILURE")).isEqualTo(1);
    }

    // 이력 INSERT·커밋 오류가 정상 인증·401·일반/전체 로그아웃과 쿠키 정리를 바꾸지 않는다
    @ParameterizedTest
    @CsvSource({
        "LOGIN_SUCCESS,false",
        "LOGIN_SUCCESS,true",
        "LOGIN_FAILURE,false",
        "LOGIN_FAILURE,true",
        "CURRENT,false",
        "CURRENT,true",
        "ALL,false",
        "ALL,true"
    })
    void record_whenInsertOrCommitFails_preservesAuthenticationContract(
            String event, boolean commit, CapturedOutput output) throws Exception {
        var browser = client();
        boolean logout = event.equals("CURRENT") || event.equals("ALL");
        if (logout) assertThat(login(browser, loginId, password).statusCode()).isEqualTo(200);
        doAnswer(invocation -> {
                    LoginHistory value = invocation.getArgument(0);
                    boolean selected = value.getUserId() != null
                            && value.getUserId().equals(userId)
                            && (logout
                                    ? value.getEventType() == LoginEventType.LOGOUT
                                    : value.getEventType().name().equals(event));
                    if (!selected) return invocation.callRealMethod();
                    if (!commit) throw new DataAccessResourceFailureException("private IP and UA must not leak");
                    Object result = invocation.callRealMethod();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw new TransactionSystemException("private commit failure");
                        }
                    });
                    return result;
                })
                .when(repository)
                .saveAndFlush(any(LoginHistory.class));
        if (logout) {
            var response =
                    send(browser, "POST", event.equals("ALL") ? "/api/auth/logout-all" : "/api/auth/logout", null);
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.headers().allValues("set-cookie"))
                    .anyMatch(value -> value.startsWith("JSESSIONID=;") && value.contains("Expires=Thu, 01 Jan 1970"));
            assertThat(send(browser, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
            assertThat(count("LOGOUT")).isZero();
        } else {
            boolean success = event.equals("LOGIN_SUCCESS");
            var response = login(browser, loginId, success ? password : "wrong");
            assertThat(response.statusCode()).isEqualTo(success ? 200 : 401);
            assertThat(send(browser, "GET", "/api/auth/me", null).statusCode()).isEqualTo(success ? 200 : 401);
            assertThat(count(event)).isZero();
            if (!success)
                assertThat(jdbc.queryForObject(
                                "SELECT login_failure_count FROM platform_users WHERE user_id=?",
                                Integer.class,
                                userId))
                        .isEqualTo(1);
        }
        assertThat(output.getAll())
                .contains("로그인 이력 저장 실패")
                .doesNotContain("private IP and UA must not leak", "private commit failure");
    }

    // 바깥 트랜잭션 롤백·인증 예외와 관계없이 실패 이력은 독립 커밋된다
    @Test
    void failure_whenOuterTransactionRollsBack_preservesCommittedHistory() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    histories.failure(userId, new org.springframework.mock.web.MockHttpServletRequest());
                    throw new org.springframework.security.authentication.BadCredentialsException("rejected");
                }))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        assertThat(count("LOGIN_FAILURE")).isEqualTo(1);
    }

    // 일반·전체 로그아웃 요청이 겹쳐도 같은 로그인의 DB 기록은 최대 한 건이다
    @Test
    void logout_whenConcurrentAcrossRoutes_recordsAtMostOnce() throws Exception {
        var browser = client();
        assertThat(login(browser, loginId, password).statusCode()).isEqualTo(200);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var requests = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 6; i++) {
                String path = i % 2 == 0 ? "/api/auth/logout" : "/api/auth/logout-all";
                requests.add(executor.submit(() -> {
                    gate.await();
                    return send(browser, "POST", path, null);
                }));
            }
            gate.countDown();
            for (var task : requests) {
                var response = task.get(15, TimeUnit.SECONDS);
                assertThat(response.statusCode())
                        .as(response.uri().getPath() + " " + response.body())
                        .isIn(204, 401);
            }
        }
        assertThat(count("LOGOUT")).isEqualTo(1);
        assertThat(send(browser, "POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        assertThat(send(browser, "POST", "/api/auth/logout-all", null).statusCode())
                .isEqualTo(401);
        assertThat(count("LOGOUT")).isEqualTo(1);
        var reference = user();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 6; i++) {
                boolean all = i % 2 == 0;
                futures.add(executor.submit(() ->
                        histories.logout(reference, all, new org.springframework.mock.web.MockHttpServletRequest())));
            }
            for (var task : futures) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("LOGOUT")).isEqualTo(2);
    }

    // 계정별 조회와 최근 성공은 정확한 90일 경계·미래 기록을 제외하고 삭제 실패 다음 실행도 복구한다
    @Test
    void retention_whenAtBoundary_filtersAndDeletesWithRetry() throws Exception {
        seed(userId, "LOGIN_SUCCESS", start.minus(Duration.ofDays(90)));
        seed(userId, "LOGIN_FAILURE", start.minus(Duration.ofDays(90)).minusNanos(1000));
        seed(userId, "LOGIN_FAILURE", start.minus(Duration.ofDays(90)).plusNanos(1000));
        seed(userId, "LOGIN_SUCCESS", start.plusSeconds(1));
        assertThat(queries.own(user(), 0, 20).totalElements()).isEqualTo(1);
        assertThat(queries.latest(user()).latestSuccess()).isNull();
        var failedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        var faultyTransactions = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                if (failedOnce.compareAndSet(false, true)) throw new TransactionSystemException("delete unavailable");
                return transactions.getTransaction(definition);
            }

            @Override
            public void commit(TransactionStatus status) {
                transactions.commit(status);
            }

            @Override
            public void rollback(TransactionStatus status) {
                transactions.rollback(status);
            }
        };
        var retrying = new LoginHistoryRetentionService(repository, faultyTransactions, clock);
        retrying.deleteExpired();
        assertThat(count("LOGIN_FAILURE")).isEqualTo(2);
        retrying.deleteExpired();
        assertThat(count("LOGIN_FAILURE")).isEqualTo(1);
        assertThat(count("LOGIN_SUCCESS")).isEqualTo(1);
        time.set(start.plusSeconds(1));
        assertThat(queries.latest(user()).latestSuccess()).isNotNull();
    }

    // 최신순 페이지는 같은 시각에도 PK 역순으로 안정적으로 정렬되고 잘못된 조건은 거절한다
    @Test
    void queries_whenTiedAndPaged_ordersByIdAndEnforcesAuthorization() throws Exception {
        var browser = client();
        assertThat(login(browser, loginId, password).statusCode()).isEqualTo(200);
        for (int i = 0; i < 22; i++) seed(userId, "LOGIN_FAILURE", start);
        var page = get(browser, "/api/auth/login-histories?page=0&size=20");
        var next = get(browser, "/api/auth/login-histories?page=1&size=20");
        assertThat(page.path("totalElements").asInt()).isEqualTo(23);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.path("items").get(19).path("historyId").asLong())
                .isGreaterThan(next.path("items").get(0).path("historyId").asLong());
        for (String query : new String[] {"page=-1", "size=0", "size=101", "page=2147483647", "page=invalid"})
            assertThat(send(browser, "GET", "/api/auth/login-histories?" + query, null)
                            .statusCode())
                    .isEqualTo(400);
        assertThat(send(client(), "GET", "/api/auth/login-histories", null).statusCode())
                .isEqualTo(401);
        assertThat(send(client(), "GET", "/api/auth/login-histories/latest-success", null)
                        .statusCode())
                .isEqualTo(401);
        assertThat(send(browser, "GET", "/api/admin/users/999999999/login-histories", null)
                        .statusCode())
                .isEqualTo(404);
        jdbc.update("UPDATE platform_users SET role='SECURITY_STAFF' WHERE user_id=?", userId);
        assertThat(login(browser, loginId, password).statusCode()).isEqualTo(200);
        assertThat(send(browser, "GET", "/api/admin/users/" + userId + "/login-histories", null)
                        .statusCode())
                .isEqualTo(403);
        assertThat(send(browser, "GET", "/api/auth/login-histories", null).statusCode())
                .isEqualTo(200);
        jdbc.update("UPDATE platform_users SET password_change_required=TRUE WHERE user_id=?", userId);
        assertThat(login(browser, loginId, password).statusCode()).isEqualTo(200);
        assertThat(send(browser, "GET", "/api/auth/login-histories", null).statusCode())
                .isEqualTo(403);
        assertThat(send(browser, "GET", "/api/auth/login-histories/latest-success", null)
                        .statusCode())
                .isEqualTo(403);
        assertThat(send(
                                browser,
                                "POST",
                                "/api/auth/password",
                                Map.of("currentPassword", password, "newPassword", "Changed123!"))
                        .statusCode())
                .isEqualTo(204);
        assertThat(send(browser, "GET", "/api/auth/login-histories", null).statusCode())
                .isEqualTo(200);
    }

    // 업무 권한 없는 사장님까지 세 역할 모두 자신의 이력을 보고 다른 계정은 ADMIN만 조회한다
    @ParameterizedTest
    @EnumSource(UserRole.class)
    void own_whenEveryRoleIncludingNoBusinessPermissions_returnsOnlyOwnHistory(UserRole role) throws Exception {
        Long merchant = role == UserRole.OPERATOR
                ? jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code='DEMOSHOP'", Long.class)
                : null;
        jdbc.update("UPDATE platform_users SET role=?, merchant_id=? WHERE user_id=?", role.name(), merchant, userId);
        Long other = account(loginId + "_other");
        seed(other, "LOGIN_FAILURE", start);
        var browser = client();
        assertThat(login(browser, loginId, password).statusCode()).isEqualTo(200);
        assertThat(get(browser, "/api/auth/login-histories?userId=" + other)
                        .path("totalElements")
                        .asInt())
                .isEqualTo(1);
        var target = send(browser, "GET", "/api/admin/users/" + other + "/login-histories", null);
        assertThat(target.statusCode()).isEqualTo(role == UserRole.ADMIN ? 200 : 403);
        assertThat(get(browser, "/api/auth/login-histories/latest-success")
                        .path("latestSuccess")
                        .isNull())
                .isFalse();
    }

    // 세션 저장 자체가 실패하면 성공 이력·새 인증은 남지 않으며 앞서 만료한 이전 세션은 복구하지 않는다
    @Test
    void login_whenSessionPersistenceFails_returnsAuth005AndCleansNewAuthentication() throws Exception {
        var previous = client();
        assertThat(login(previous, loginId, password).statusCode()).isEqualTo(200);
        doAnswer(invocation -> {
                    var context = (org.springframework.security.core.context.SecurityContext) invocation.getArgument(0);
                    Object result = invocation.callRealMethod();
                    if (context.getAuthentication() != null
                            && context.getAuthentication().getPrincipal() instanceof SessionUser current
                            && current.userId().equals(userId)) throw new IllegalStateException("session write failed");
                    return result;
                })
                .when(contexts)
                .saveContext(any(), any(), any());
        var fresh = client();
        var response = login(fresh, loginId, password);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo("AUTH_005");
        assertThat(send(fresh, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(send(previous, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(count("LOGIN_SUCCESS")).isEqualTo(1);
        assertThat(count("LOGIN_FAILURE")).isZero();
    }

    // 만료한 성공·실패·로그아웃과 NULL 계정 기록은 예전 10배치 상한(10,000건)을 넘어도 한 번에 모두 삭제하며
    // 최근 성공을 보존하지 않는다
    @Test
    void retention_whenOverBatchSize_deletesEveryExpiredKindAndClearsLatest() {
        var expired = LocalDateTime.ofInstant(start.minus(Duration.ofDays(90)), ZoneOffset.UTC);
        jdbc.batchUpdate(
                "INSERT INTO login_histories(user_id,event_type,occurred_at) VALUES (?, 'LOGIN_FAILURE', ?)",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public int getBatchSize() {
                        return 10_003;
                    }

                    @Override
                    public void setValues(java.sql.PreparedStatement statement, int i) throws java.sql.SQLException {
                        if (i == 0) statement.setNull(1, java.sql.Types.BIGINT);
                        else statement.setLong(1, userId);
                        statement.setObject(2, expired);
                    }
                });
        seed(userId, "LOGIN_SUCCESS", start.minus(Duration.ofDays(90)));
        jdbc.update(
                "INSERT INTO login_histories(user_id,event_type,occurred_at,login_reference,logout_scope) VALUES (?, 'LOGOUT', ?, ?, 'ALL')",
                userId,
                expired,
                UUID.randomUUID().toString());
        retention.deleteExpired();
        assertThat(count("LOGIN_FAILURE")).isZero();
        assertThat(count("LOGIN_SUCCESS")).isZero();
        assertThat(count("LOGOUT")).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM login_histories WHERE user_id IS NULL AND occurred_at <= ?",
                        Integer.class,
                        expired))
                .isZero();
        assertThat(queries.latest(user()).latestSuccess()).isNull();
    }
}
