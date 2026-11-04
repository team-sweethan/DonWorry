package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.OperatorAccountSuspender;
import com.donworry.identity.api.OperatorAccountSuspensionException;
import com.donworry.identity.api.OperatorAccountSuspensionException.Reason;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.service.AccountLockService;
import com.donworry.identity.service.AdminChangeHistoryService;
import com.donworry.identity.service.UserSessionService;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperatorAccountSuspensionMariaDbTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    OperatorAccountSuspender suspender;

    @Autowired
    SessionRegistry registry;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    PlatformUserRepository users;

    @MockitoSpyBean
    AdminChangeHistoryService histories;

    @MockitoSpyBean
    UserSessionService sessions;

    @MockitoSpyBean
    AccountLockService locks;

    private String prefix;
    private long merchantId;
    private long ownerId;
    private String ownerLogin;

    @BeforeEach
    void setUp() {
        prefix = "s517_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update(
                "INSERT INTO merchants(merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,'ACTIVE',0.02,UTC_TIMESTAMP(6))",
                prefix,
                prefix);
        merchantId = jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, prefix);
        ownerLogin = prefix + "_owner";
        ownerId = account(ownerLogin, "OPERATOR");
    }

    private long account(String login, String role) {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required) VALUES (?,?,'정지 테스트','ACTIVE',?,?,UTC_TIMESTAMP(6),false)",
                login,
                encoder.encode("OwnerPass123"),
                role,
                role.equals("OPERATOR") ? merchantId : null);
        return jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, login);
    }

    @AfterEach
    void cleanUp() {
        var ids =
                jdbc.queryForList("SELECT user_id FROM platform_users WHERE login_id LIKE ?", Long.class, prefix + "%");
        for (long id : ids) {
            sessions.expireAll(id);
            jdbc.update("DELETE FROM operator_access_denials WHERE user_id=?", id);
            jdbc.update("DELETE FROM login_histories WHERE user_id=?", id);
            jdbc.update("DELETE FROM admin_change_histories WHERE actor_user_id=? OR target_user_id=?", id, id);
        }
        for (long id : ids) jdbc.update("DELETE FROM platform_users WHERE user_id=?", id);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchantId);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, Object body) throws Exception {
        return com.donworry.HttpCsrfTestSupport.send(
                client,
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }

    private HttpResponse<String> login(HttpClient client, String login) throws Exception {
        return send(client, "POST", "/api/auth/login", Map.of("loginId", login, "password", "OwnerPass123"));
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM platform_users WHERE user_id=?", String.class, ownerId);
    }

    private int count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_change_histories WHERE target_user_id=?", Integer.class, ownerId);
    }

    // 정지는 모든 세션·로그인을 차단하고 기존 관리자 해제로 다시 로그인할 수 있다
    @Test
    void suspend_whenActive_blocksSessionsAndLoginUntilAdminReactivates() throws Exception {
        var owner = client();
        assertThat(login(owner, ownerLogin).statusCode()).isEqualTo(200);
        registry.registerNewSession(prefix + "_extra", ownerId);
        var sessionIds = registry.getAllSessions(ownerId, false).stream()
                .map(s -> s.getSessionId())
                .toList();
        var merchantBefore = jdbc.queryForMap("SELECT * FROM merchants WHERE merchant_id=?", merchantId);
        suspender.suspend(ownerId);
        assertThat(status()).isEqualTo("INACTIVE");
        for (String sessionId : sessionIds)
            assertThat(registry.getSessionInformation(sessionId).isExpired()).isTrue();
        assertThat(send(owner, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
        var denied = login(client(), ownerLogin);
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(mapper.readTree(denied.body()).path("code").asString()).isEqualTo("AUTH_004");
        assertThat(denied.body()).doesNotContain("FDS", "USER_SUSPENDED");
        var history = jdbc.queryForMap("SELECT * FROM admin_change_histories WHERE target_user_id=?", ownerId);
        assertThat(history.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(history.get("actor_user_id")).isNull();
        assertThat(history.get("change_type")).isEqualTo("USER_SUSPENDED_BY_FDS");
        assertThat(mapper.readTree(history.get("before_data").toString())
                        .path("status")
                        .asString())
                .isEqualTo("ACTIVE");
        assertThat(mapper.readTree(history.get("after_data").toString())
                        .path("status")
                        .asString())
                .isEqualTo("INACTIVE");
        assertThat(jdbc.queryForMap("SELECT * FROM merchants WHERE merchant_id=?", merchantId))
                .isEqualTo(merchantBefore);
        suspender.suspend(ownerId);
        assertThat(count()).isEqualTo(1);
        verify(sessions, times(1)).expireAll(ownerId);
        String adminLogin = prefix + "_admin";
        long adminId = account(adminLogin, "ADMIN");
        var admin = client();
        assertThat(login(admin, adminLogin).statusCode()).isEqualTo(200);
        assertThat(send(admin, "PUT", "/api/admin/users/" + ownerId + "/status", Map.of("status", "ACTIVE"))
                        .statusCode())
                .isEqualTo(204);
        assertThat(login(client(), ownerLogin).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT actor_type FROM admin_change_histories WHERE target_user_id=? AND actor_user_id=?",
                        String.class,
                        ownerId,
                        adminId))
                .isEqualTo("STAFF");
    }

    // 비활성 직원도 성공으로 취급하지 않고 공개 예외로 거절한다
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SECURITY_STAFF"})
    void suspend_whenStaff_rejectsBothStatuses(String role) {
        long id = account(prefix + "_staff", role);
        for (String state : List.of("ACTIVE", "INACTIVE")) {
            jdbc.update("UPDATE platform_users SET status=? WHERE user_id=?", state, id);
            assertThatThrownBy(() -> suspender.suspend(id))
                    .isInstanceOfSatisfying(
                            OperatorAccountSuspensionException.class,
                            e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_OPERATOR));
            assertThat(jdbc.queryForObject("SELECT status FROM platform_users WHERE user_id=?", String.class, id))
                    .isEqualTo(state);
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM admin_change_histories WHERE target_user_id=?", Integer.class, id))
                    .isZero();
        }
    }

    // 유효하지 않은 ID와 없는 계정은 상태 변경 전에 구분하여 거절한다
    @Test
    void suspend_whenInvalidOrMissing_rejectsWithPublicContract() {
        for (Long id : Arrays.asList(null, 0L, -1L))
            assertThatThrownBy(() -> suspender.suspend(id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> suspender.suspend(Long.MAX_VALUE))
                .isInstanceOfSatisfying(
                        OperatorAccountSuspensionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.USER_NOT_FOUND));
        assertThat(status()).isEqualTo("ACTIVE");
        assertThat(count()).isZero();
    }

    // 감사 저장에 실패하면 실제 DB 상태와 세션을 보존하고 재시도가 가능하다
    @Test
    void suspend_whenAuditFails_rollsBackAndCanRetry() throws Exception {
        var owner = client();
        assertThat(login(owner, ownerLogin).statusCode()).isEqualTo(200);
        var spy = AopTestUtils.<AdminChangeHistoryService>getUltimateTargetObject(histories);
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(spy)
                .recordSystemUser(eq(ownerId), anyString(), any(), any());
        try {
            assertThatThrownBy(() -> suspender.suspend(ownerId)).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(status()).isEqualTo("ACTIVE");
            assertThat(count()).isZero();
            assertThat(send(owner, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        } finally {
            doCallRealMethod().when(spy).recordSystemUser(eq(ownerId), anyString(), any(), any());
        }
        suspender.suspend(ownerId);
        assertThat(status()).isEqualTo("INACTIVE");
        assertThat(count()).isEqualTo(1);
    }

    // 호출자 롤백은 이미 커밋된 정지와 감사·세션 만료를 되돌리지 않는다
    @Test
    void suspend_whenCallerRollsBack_keepsIndependentCommit() {
        registry.registerNewSession(prefix, ownerId);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            // 외부 영속성 컨텍스트에 ACTIVE가 있어도 내부 재조회는 분리된다.
            var previous = users.findById(ownerId).orElseThrow();
            assertThat(previous.getStatus()).isEqualTo(UserStatus.ACTIVE);
            suspender.suspend(ownerId);
            assertThat(previous.getStatus()).isEqualTo(UserStatus.ACTIVE);
            tx.setRollbackOnly();
        });
        assertThat(status()).isEqualTo("INACTIVE");
        assertThat(count()).isEqualTo(1);
        assertThat(registry.getSessionInformation(prefix).isExpired()).isTrue();
    }

    // 동시에 정지해도 감사와 세션 만료는 한 번만 발생한다
    @Test
    void suspend_whenConcurrent_writesOneAuditAndExpiresOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++)
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    suspender.suspend(ownerId);
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertThat(status()).isEqualTo("INACTIVE");
        assertThat(count()).isEqualTo(1);
        verify(sessions, times(1)).expireAll(ownerId);
    }

    // 관리자 해제와 정지는 커밋 후 세션 만료까지 같은 계정 잠금으로 직렬화한다
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void suspend_whenRacingAdminReactivation_serializesThroughSessionExpiration(boolean suspendFirst) throws Exception {
        String adminLogin = prefix + "_admin";
        account(adminLogin, "ADMIN");
        var admin = client();
        assertThat(login(admin, adminLogin).statusCode()).isEqualTo(200);
        if (!suspendFirst) jdbc.update("UPDATE platform_users SET status='INACTIVE' WHERE user_id=?", ownerId);
        var expirationEntered = new CountDownLatch(1);
        var releaseExpiration = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var firstExpiration = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    if (firstExpiration.getAndSet(false)) {
                        expirationEntered.countDown();
                        await(releaseExpiration);
                    }
                    return invocation.callRealMethod();
                })
                .when(sessions)
                .expireAll(ownerId);
        if (suspendFirst) {
            doAnswer(invocation -> {
                        secondEntered.countDown();
                        return invocation.callRealMethod();
                    })
                    .when(locks)
                    .withAccounts(eq(adminLogin), eq(ownerLogin), any());
        } else {
            doAnswer(invocation -> {
                        secondEntered.countDown();
                        return invocation.callRealMethod();
                    })
                    .when(locks)
                    .withAccount(eq(ownerLogin), any());
        }
        Callable<Void> suspend = () -> {
            suspender.suspend(ownerId);
            return null;
        };
        Callable<Void> reactivate = () -> {
            assertThat(send(admin, "PUT", "/api/admin/users/" + ownerId + "/status", Map.of("status", "ACTIVE"))
                            .statusCode())
                    .isEqualTo(204);
            return null;
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(suspendFirst ? suspend : reactivate);
            try {
                assertThat(expirationEntered.await(10, TimeUnit.SECONDS)).isTrue();
                // 세션 만료 전에도 DB 커밋은 완료되어 있다.
                assertThat(status()).isEqualTo(suspendFirst ? "INACTIVE" : "ACTIVE");
                var second = executor.submit(suspendFirst ? reactivate : suspend);
                assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseExpiration.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                releaseExpiration.countDown();
            }
        }
        assertThat(status()).isEqualTo(suspendFirst ? "ACTIVE" : "INACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM admin_change_histories WHERE target_user_id=? AND actor_type='SYSTEM'",
                        Integer.class,
                        ownerId))
                .isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
