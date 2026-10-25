package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.TenantReader;
import com.donworry.identity.service.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
class AdminMerchantHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    TenantReader tenants;

    @MockitoSpyBean
    AdminChangeHistoryService histories;

    @MockitoSpyBean
    UserSessionService sessions;

    private String prefix;
    private String code;
    private long adminId;
    private long merchantId;
    private HttpClient admin;

    @BeforeEach
    void setUp() throws Exception {
        prefix = "a130_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        code = prefix.replace("_", "").toUpperCase(Locale.ROOT);
        adminId = user("admin", "ADMIN", null);
        admin = client();
        assertThat(login(admin, "admin").statusCode()).isEqualTo(200);
        merchantId = create(code).path("merchantId").asLong();
    }

    @AfterEach
    void cleanUp() {
        var ids =
                jdbc.queryForList("SELECT user_id FROM platform_users WHERE login_id LIKE ?", Long.class, prefix + "%");
        for (Long id : ids) {
            jdbc.update("DELETE FROM admin_change_histories WHERE actor_user_id=? OR target_user_id=?", id, id);
            jdbc.update("DELETE FROM user_permissions WHERE user_id=?", id);
            sessions.expireAll(id);
        }
        for (Long id : ids) jdbc.update("DELETE FROM platform_users WHERE user_id=?", id);
        jdbc.update("DELETE FROM merchants WHERE merchant_code LIKE ?", code + "%");
    }

    private long user(String suffix, String role, Long merchant) {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at) VALUES (?,?,'테스트','ACTIVE',?,?,UTC_TIMESTAMP(6))",
                prefix + suffix,
                encoder.encode("MerchantPass123"),
                role,
                merchant);
        return jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, prefix + suffix);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> send(HttpClient caller, String method, String path, Object body) throws Exception {
        return caller.send(
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

    private HttpResponse<String> login(HttpClient caller, String suffix) throws Exception {
        return loginAs(caller, prefix + suffix);
    }

    private HttpResponse<String> loginAs(HttpClient caller, String loginId) throws Exception {
        return send(caller, "POST", "/api/auth/login", Map.of("loginId", loginId, "password", "MerchantPass123"));
    }

    private JsonNode create(String merchantCode) throws Exception {
        var response = send(admin, "POST", "/api/admin/merchants", input(merchantCode, "0.010000"));
        assertThat(response.statusCode()).isEqualTo(201);
        return mapper.readTree(response.body());
    }

    private Map<String, String> input(String merchantCode, String fee) {
        return Map.of("merchantCode", merchantCode, "displayName", "가맹점 테스트", "pgFeeRate", fee);
    }

    private HttpResponse<String> status(String next) throws Exception {
        return send(admin, "PUT", "/api/admin/merchants/" + merchantId + "/status", Map.of("status", next));
    }

    private void problem(HttpResponse<String> response, int expected, String code) {
        assertThat(response.statusCode()).isEqualTo(expected);
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("passwordHash", "$2a$", "MerchantPass123", "secret_reference");
    }

    private int historyCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_change_histories WHERE target_merchant_id=?", Integer.class, merchantId);
    }

    // 관리자 외 역할과 익명은 모든 가맹점 관리 API에 접근할 수 없다
    @Test
    void admin_whenNotAdmin_rejectsEveryEndpoint() throws Exception {
        user("staff", "SECURITY_STAFF", null);
        user("owner", "OPERATOR", merchantId);
        for (String role : List.of("staff", "owner", "anonymous")) {
            var caller = client();
            boolean anonymous = role.equals("anonymous");
            if (!anonymous) assertThat(login(caller, role).statusCode()).isEqualTo(200);
            int expected = anonymous ? 401 : 403;
            String error = anonymous ? "AUTH_001" : "AUTH_002";
            for (String path : List.of(
                    "/api/admin/merchants", "/api/admin/merchants/" + merchantId, "/api/admin/merchants/options"))
                problem(send(caller, "GET", path, null), expected, error);
            problem(send(caller, "POST", "/api/admin/merchants", input(code + "X", "0.01")), expected, error);
            problem(
                    send(caller, "PUT", "/api/admin/merchants/" + merchantId + "/status", Map.of("status", "INACTIVE")),
                    expected,
                    error);
        }
        jdbc.update("UPDATE platform_users SET password_change_required=TRUE WHERE user_id=?", adminId);
        var forced = client();
        assertThat(login(forced, "admin").statusCode()).isEqualTo(200);
        problem(send(forced, "POST", "/api/admin/merchants", input(code + "F", "0.01")), 403, "AUTH_008");
    }

    // 생성·조회·중복과 수수료율 경계는 실제 DB 정밀도로 검증한다
    @Test
    void create_whenInputValidated_persistsExactRateAndQueries() throws Exception {
        assertThat(historyCount()).isOne();
        var detail = send(admin, "GET", "/api/admin/merchants/" + merchantId, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).doesNotContain("webhook", "secret");
        var list = mapper.readTree(
                send(admin, "GET", "/api/admin/merchants?keyword=" + code + "&size=1&status=ACTIVE", null)
                        .body());
        assertThat(list.path("totalElements").asLong()).isOne();
        assertThat(list.path("items").get(0).path("merchantId").asLong()).isEqualTo(merchantId);
        problem(send(admin, "POST", "/api/admin/merchants", input(code, "0.01")), 409, "ADMIN_009");
        for (String invalid : List.of("AB", "lower", "A_B", "A".repeat(41)))
            problem(send(admin, "POST", "/api/admin/merchants", input(invalid, "0.01")), 400, "ADMIN_007");
        for (String invalid : List.of("-0.000001", "1", "0.1234567", "NaN", ""))
            problem(send(admin, "POST", "/api/admin/merchants", input(code + "BAD", invalid)), 400, "ADMIN_007");
        for (String fee : List.of("0", "0.000001", "0.999999")) {
            String newCode = code + fee.replace(".", "");
            var row = createWithFee(newCode, fee);
            assertThat(jdbc.queryForObject(
                            "SELECT pg_fee_rate FROM merchants WHERE merchant_id=?", java.math.BigDecimal.class, row))
                    .isEqualByComparingTo(fee);
        }
        problem(
                send(
                        admin,
                        "POST",
                        "/api/admin/merchants",
                        Map.of("merchantCode", code + "B", "displayName", " ", "pgFeeRate", "0.01")),
                400,
                "ADMIN_007");
        problem(send(admin, "POST", "/api/admin/merchants", Map.of()), 400, "ADMIN_007");
        for (String query : List.of("page=-1", "page=abc", "size=101", "status=UNKNOWN", "page=2147483647"))
            problem(send(admin, "GET", "/api/admin/merchants?" + query, null), 400, "ADMIN_007");
        problem(send(admin, "GET", "/api/admin/merchants/abc", null), 400, "ADMIN_007");
        problem(send(admin, "GET", "/api/admin/merchants/999999999", null), 404, "ADMIN_008");
        assertThat(mapper.readTree(send(admin, "GET", "/api/admin/merchants?keyword=%25", null)
                                .body())
                        .path("totalElements")
                        .asLong())
                .isZero();
    }

    private long createWithFee(String newCode, String fee) throws Exception {
        var response = send(admin, "POST", "/api/admin/merchants", input(newCode, fee));
        assertThat(response.statusCode()).isEqualTo(201);
        return mapper.readTree(response.body()).path("merchantId").asLong();
    }

    // 정지 시 여러 소속 세션만 종료하고 재활성화도 별도 정지 계정과 옛 세션을 복구하지 않는다
    @Test
    void status_whenDisabledAndEnabled_preservesAccountStateAndTenantContract() throws Exception {
        long ownerId = user("owner", "OPERATOR", merchantId);
        user("owner2", "OPERATOR", merchantId);
        long disabledId = user("disabled", "OPERATOR", merchantId);
        jdbc.update("UPDATE platform_users SET status='DISABLED' WHERE user_id=?", disabledId);
        long otherMerchant = create(code + "OTHER").path("merchantId").asLong();
        user("other", "OPERATOR", otherMerchant);
        var owner = client();
        var owner2 = client();
        var other = client();
        assertThat(login(owner, "owner").statusCode()).isEqualTo(200);
        assertThat(login(owner2, "owner2").statusCode()).isEqualTo(200);
        assertThat(login(other, "other").statusCode()).isEqualTo(200);
        assertThat(status("INACTIVE").statusCode()).isEqualTo(204);
        assertThat(tenants.findById(merchantId).orElseThrow().status()).isEqualTo("INACTIVE");
        problem(send(owner, "GET", "/api/auth/me", null), 401, "AUTH_001");
        problem(send(owner2, "GET", "/api/auth/me", null), 401, "AUTH_001");
        assertThat(send(other, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        var merchantFailure = login(client(), "owner");
        var disabledFailure = login(client(), "disabled");
        problem(merchantFailure, 401, "AUTH_004");
        assertThat(mapper.readTree(merchantFailure.body()).path("detail"))
                .isEqualTo(mapper.readTree(disabledFailure.body()).path("detail"));
        assertThat(jdbc.queryForObject("SELECT status FROM platform_users WHERE user_id=?", String.class, ownerId))
                .isEqualTo("ACTIVE");
        int count = historyCount();
        assertThat(status("INACTIVE").statusCode()).isEqualTo(204);
        assertThat(historyCount()).isEqualTo(count);
        problem(
                send(
                        admin,
                        "POST",
                        "/api/admin/users",
                        Map.of(
                                "loginId",
                                prefix + "new",
                                "displayName",
                                "사장님",
                                "role",
                                "OPERATOR",
                                "merchantId",
                                merchantId)),
                400,
                "ADMIN_005");
        assertThat(send(
                                admin,
                                "GET",
                                "/api/admin/merchants/options?keyword="
                                        + URLEncoder.encode("가맹점 테스트", java.nio.charset.StandardCharsets.UTF_8),
                                null)
                        .body())
                .doesNotContain("\"merchantId\":" + merchantId + ",");
        assertThat(status("ACTIVE").statusCode()).isEqualTo(204);
        assertThat(tenants.findById(merchantId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(login(client(), "owner").statusCode()).isEqualTo(200);
        problem(login(client(), "disabled"), 401, "AUTH_004");
        problem(send(owner, "GET", "/api/auth/me", null), 401, "AUTH_001");
        assertThat(historyCount()).isEqualTo(count + 1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM admin_change_histories WHERE target_merchant_id=? AND target_user_id IS NOT NULL",
                        Integer.class,
                        merchantId))
                .isZero();
    }

    // 이력 INSERT 실패는 상태·생성을 롤백하고 세션을 보존한다
    @Test
    void mutation_whenAuditFails_rollsBackAndKeepsSessions() throws Exception {
        user("owner", "OPERATOR", merchantId);
        var owner = client();
        assertThat(login(owner, "owner").statusCode()).isEqualTo(200);
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when((AdminChangeHistoryService) AopTestUtils.getUltimateTargetObject(histories))
                .recordMerchant(anyLong(), anyLong(), anyString(), any(), any());
        problem(status("INACTIVE"), 500, "AUTH_007");
        assertThat(tenants.findById(merchantId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(historyCount()).isOne();
        assertThat(send(owner, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        problem(send(admin, "POST", "/api/admin/merchants", input(code + "FAIL", "0.01")), 500, "AUTH_007");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchants WHERE merchant_code=?", Integer.class, code + "FAIL"))
                .isZero();
    }

    private void pauseMerchantAudit(CountDownLatch reached, CountDownLatch release) {
        doAnswer(invocation -> {
                    reached.countDown();
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                    return invocation.callRealMethod();
                })
                .when((AdminChangeHistoryService) AopTestUtils.getUltimateTargetObject(histories))
                .recordMerchant(anyLong(), anyLong(), anyString(), any(), any());
    }

    // 정지가 먼저 시작되면 별칭 로그인과 새 계정 생성은 대기 후 정지 상태를 읽는다
    @Test
    void disable_whenFirst_blocksLoginAndCreationUntilCommit() throws Exception {
        user("owner", "OPERATOR", merchantId);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseMerchantAudit(reached, release);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var mutation = executor.submit(() -> status("INACTIVE"));
            try {
                assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
                var loggingIn = executor.submit(() -> loginAs(client(), (prefix + "owner").replaceFirst("a", "á")));
                var creation = executor.submit(() -> send(
                        admin,
                        "POST",
                        "/api/admin/users",
                        Map.of(
                                "loginId",
                                prefix + "new",
                                "displayName",
                                "사장님",
                                "role",
                                "OPERATOR",
                                "merchantId",
                                merchantId)));
                assertThatThrownBy(() -> loggingIn.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> creation.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(mutation.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
                problem(loggingIn.get(10, TimeUnit.SECONDS), 401, "AUTH_004");
                problem(creation.get(10, TimeUnit.SECONDS), 400, "ADMIN_005");
            } finally {
                release.countDown();
            }
        }
    }

    // 로그인이 먼저 세션 등록에 진입하면 정지는 기다린 뒤 해당 새 세션까지 종료한다
    @Test
    void disable_whenLoginFirst_waitsAndExpiresNewSession() throws Exception {
        user("owner", "OPERATOR", merchantId);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    reached.countDown();
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                    return invocation.callRealMethod();
                })
                .when(sessions)
                .establishAuthentication(any(), any(), any());
        var caller = client();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var loggingIn = executor.submit(() -> login(caller, "owner"));
            try {
                assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
                var mutation = executor.submit(() -> status("INACTIVE"));
                assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(loggingIn.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(mutation.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
                problem(send(caller, "GET", "/api/auth/me", null), 401, "AUTH_001");
            } finally {
                release.countDown();
            }
        }
    }

    // 계정 생성이 먼저 시작되면 정지는 커밋을 기다린 뒤 새 계정까지 포함한다
    @Test
    void disable_whenCreationFirst_waitsForNewAccountCommit() throws Exception {
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    reached.countDown();
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                    return invocation.callRealMethod();
                })
                .when((AdminChangeHistoryService) AopTestUtils.getUltimateTargetObject(histories))
                .recordUser(anyLong(), anyLong(), anyString(), any(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var creation = executor.submit(() -> send(
                    admin,
                    "POST",
                    "/api/admin/users",
                    Map.of(
                            "loginId",
                            prefix + "new",
                            "displayName",
                            "사장님",
                            "role",
                            "OPERATOR",
                            "merchantId",
                            merchantId)));
            try {
                assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
                var mutation = executor.submit(() -> status("INACTIVE"));
                assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                var response = creation.get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(201);
                long newId = mapper.readTree(response.body())
                        .path("user")
                        .path("userId")
                        .asLong();
                assertThat(mutation.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
                verify(sessions).expireAll(newId);
            } finally {
                release.countDown();
            }
        }
    }
    // 서로 다른 관리자의 동시 중복 생성은 한 건만 저장하고 나머지를 409로 응답한다
    @Test
    void create_whenConcurrentDuplicate_returnsConflictWithoutDuplicateHistory() throws Exception {
        user("admis", "ADMIN", null);
        var secondAdmin = client();
        assertThat(login(secondAdmin, "admis").statusCode()).isEqualTo(200);
        String duplicate = code + "RACE";
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return send(admin, "POST", "/api/admin/merchants", input(duplicate, "0.01"));
            });
            var second = executor.submit(() -> {
                start.await();
                return send(secondAdmin, "POST", "/api/admin/merchants", input(duplicate, "0.01"));
            });
            try {
                start.countDown();
                var responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                        .containsExactlyInAnyOrder(201, 409);
                problem(
                        responses.stream()
                                .filter(r -> r.statusCode() == 409)
                                .findFirst()
                                .orElseThrow(),
                        409,
                        "ADMIN_009");
                assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM merchants WHERE merchant_code=?", Integer.class, duplicate))
                        .isOne();
                assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_change_histories h JOIN merchants m ON h.target_merchant_id=m.merchant_id WHERE m.merchant_code=?",
                                Integer.class,
                                duplicate))
                        .isOne();
            } finally {
                start.countDown();
            }
        }
    }
}
