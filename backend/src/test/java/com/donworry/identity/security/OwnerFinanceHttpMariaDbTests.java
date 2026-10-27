package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.identity.service.UserSessionService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

// P6 업무 API가 아닌 테스트 수신부로 실제 로그인·공개 조회·공통 인가의 연결만 검증한다
@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "security.csrf.enabled=false")
@Import({MariaDbTestContainerConfig.class, OwnerFinanceHttpMariaDbTests.ProbeController.class})
class OwnerFinanceHttpMariaDbTests {
    private static final String PROBE = "/api/v1/settlements/auth-probe";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    UserSessionService sessions;

    private String prefix;
    private Long merchantId;
    private Long ownerId;
    private HttpClient owner;
    private HttpClient admin;

    @BeforeEach
    void setUp() throws Exception {
        prefix = "f240_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update(
                "INSERT INTO merchants (merchant_code,display_name,status,pg_fee_rate,created_at) "
                        + "VALUES (?, 'finance test', 'ACTIVE', 0.01, UTC_TIMESTAMP(6))",
                prefix);
        merchantId = jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, prefix);
        ownerId = user("owner", "OPERATOR", merchantId, true);
        user("admin", "ADMIN", null, false);
        owner = client();
        admin = client();
        assertThat(login(admin, "admin").statusCode()).isEqualTo(200);
        assertThat(login(owner, "owner").statusCode()).isEqualTo(200);
    }

    @AfterEach
    void cleanUp() {
        var ids =
                jdbc.queryForList("SELECT user_id FROM platform_users WHERE login_id LIKE ?", Long.class, prefix + "%");
        for (Long id : ids) {
            sessions.expireAll(id);
            jdbc.update("DELETE FROM login_histories WHERE user_id=?", id);
            jdbc.update("DELETE FROM admin_change_histories WHERE actor_user_id=? OR target_user_id=?", id, id);
            jdbc.update("DELETE FROM user_permissions WHERE user_id=?", id);
        }
        for (Long id : ids) jdbc.update("DELETE FROM platform_users WHERE user_id=?", id);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchantId);
    }

    private long user(String suffix, String role, Long merchant, boolean permission) {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at) "
                        + "VALUES (?,?,'finance test','ACTIVE',?,?,UTC_TIMESTAMP(6))",
                prefix + suffix,
                encoder.encode("FinancePass123"),
                role,
                merchant);
        long id =
                jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, prefix + suffix);
        if (permission)
            jdbc.update("INSERT INTO user_permissions(user_id,permission_code) VALUES (?,'FINANCE_VIEW')", id);
        return id;
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
        return send(
                caller, "POST", "/api/auth/login", Map.of("loginId", prefix + suffix, "password", "FinancePass123"));
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("application/problem+json");
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("location")).isEmpty();
    }

    // 실제 쿠키의 가맹점이 공개 API로 전달되고 CSRF 기본 비활성의 읽기·쓰기가 유지된다
    @Test
    void finance_whenRealOwnerSession_exposesAuthenticatedMerchantOnly() throws Exception {
        problem(send(client(), "GET", PROBE, null), 401, "AUTH_001");
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            var response = send(owner, method, PROBE + "?merchantId=999999", Map.of("merchantId", 999999));
            assertThat(response.statusCode()).isEqualTo(200);
            var summary = mapper.readTree(response.body());
            assertThat(summary.path("merchantId").asLong()).isEqualTo(merchantId);
            assertThat(summary.path("userId").asLong()).isEqualTo(ownerId);
        }
        // 실제 P6 Controller가 없는 경로도 비로그인은 차단하고 정상 세션만 라우팅까지 진행한다.
        problem(send(client(), "GET", "/api/v1/settlements/report", null), 401, "AUTH_001");
        assertThat(send(owner, "GET", "/api/v1/settlements/report", null).statusCode())
                .isEqualTo(404);
        problem(send(admin, "GET", PROBE, null), 403, "AUTH_002");
        var me = mapper.readTree(send(admin, "GET", "/api/auth/me", null).body());
        assertThat(me.path("permissions").toString()).contains("FINANCE_VIEW");
        user("staff", "SECURITY_STAFF", null, true);
        user("no_permission", "OPERATOR", merchantId, false);
        for (String suffix : List.of("staff", "no_permission")) {
            var denied = client();
            assertThat(login(denied, suffix).statusCode()).isEqualTo(200);
            problem(send(denied, "GET", PROBE, null), 403, "AUTH_002");
            problem(send(denied, "PUT", PROBE, Map.of()), 403, "AUTH_002");
        }
    }

    // 관리 API로 권한을 회수하면 기존 세션이 종료되고 재로그인 후에는 403이 된다
    @Test
    void finance_whenPermissionRevoked_expiresSessionAndDeniesNextLoginScope() throws Exception {
        assertThat(send(admin, "PUT", "/api/admin/users/" + ownerId + "/permissions", Map.of("permissions", List.of()))
                        .statusCode())
                .isEqualTo(204);
        problem(send(owner, "GET", PROBE, null), 401, "AUTH_001");
        assertThat(login(owner, "owner").statusCode()).isEqualTo(200);
        problem(send(owner, "GET", PROBE, null), 403, "AUTH_002");
    }

    // 계정 정지는 관리 API의 기존 세션 종료 흐름을 통해 업무 요청과 재로그인을 막는다
    @Test
    void finance_whenAccountDisabled_rejectsOldSessionAndLogin() throws Exception {
        assertThat(send(admin, "PUT", "/api/admin/users/" + ownerId + "/status", Map.of("status", "DISABLED"))
                        .statusCode())
                .isEqualTo(204);
        problem(send(owner, "PUT", PROBE, Map.of()), 401, "AUTH_001");
        problem(login(owner, "owner"), 401, "AUTH_004");
    }

    // 가맹점 정지도 기존 세션 종료와 로그인 차단을 정산 경로에서 유지한다
    @Test
    void finance_whenMerchantInactive_rejectsOldSessionAndLogin() throws Exception {
        assertThat(send(admin, "PUT", "/api/admin/merchants/" + merchantId + "/status", Map.of("status", "INACTIVE"))
                        .statusCode())
                .isEqualTo(204);
        problem(send(owner, "GET", PROBE, null), 401, "AUTH_001");
        problem(login(owner, "owner"), 401, "AUTH_004");
    }

    // 새 로그인으로 만료된 쿠키와 로그아웃 쿠키는 더 이상 정산 경로를 통과하지 못한다
    @Test
    void finance_whenSessionExpiredOrLoggedOut_returnsUnauthorized() throws Exception {
        var next = client();
        assertThat(login(next, "owner").statusCode()).isEqualTo(200);
        problem(send(owner, "GET", PROBE, null), 401, "AUTH_001");
        assertThat(send(next, "GET", PROBE, null).statusCode()).isEqualTo(200);
        assertThat(send(next, "POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        problem(send(next, "GET", PROBE, null), 401, "AUTH_001");
    }

    @RestController
    static class ProbeController {
        private final CurrentStaff currentStaff;

        ProbeController(CurrentStaff currentStaff) {
            this.currentStaff = currentStaff;
        }

        @RequestMapping(PROBE)
        StaffSummary probe() {
            return currentStaff.findCurrent().orElseThrow();
        }
    }
}
