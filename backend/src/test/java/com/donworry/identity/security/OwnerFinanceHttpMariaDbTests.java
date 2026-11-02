package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.service.UserSessionService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

// 실제 P6 현금흐름 설정 API로 실제 로그인·세션 가맹점·공통 인가의 연결을 검증한다.
// 정산 규칙은 등록한 메서드·경로만 열므로 테스트 전용 경로를 쓰면 허용 여부와 관계없이 거절된다 (ADR-0066)
@Tag("mariadb")
// P6 사장님 API는 기본 비활성이라 이 테스트에서만 켠다 (docs/settlement/balance-expenses-api.md)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "settlement.owner-http.enabled=true")
@Import(MariaDbTestContainerConfig.class)
// 테스트 전용 컨텍스트의 연결 풀을 반납해 뒤의 동시성 검사가 DB 연결 한도를 넘지 않게 한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnerFinanceHttpMariaDbTests {
    private static final String SETTINGS = "/api/v1/settlements/cash-flow/settings";

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
        jdbc.update("DELETE FROM cash_flow_settings WHERE merchant_id=?", merchantId);
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
        return com.donworry.HttpCsrfTestSupport.send(
                caller,
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }

    private HttpResponse<String> login(HttpClient caller, String suffix) throws Exception {
        return send(
                caller, "POST", "/api/auth/login", Map.of("loginId", prefix + suffix, "password", "FinancePass123"));
    }

    // 필터를 통과해 P6 업무 코드까지 갔다는 표시: 설정이 없는 가맹점은 업무 오류 404다
    private void reachedSettings(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("SETTLEMENT_SETTING_REQUIRED");
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("application/problem+json");
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("location")).isEmpty();
    }

    // 실제 쿠키의 가맹점이 업무 API로 전달되고, 요청에 넣은 다른 가맹점 번호는 쓰이지 않는다
    @Test
    void finance_whenRealOwnerSession_exposesAuthenticatedMerchantOnly() throws Exception {
        problem(send(client(), "GET", SETTINGS, null), 401, "AUTH_001");
        reachedSettings(send(owner, "GET", SETTINGS + "?merchantId=999999", null));
        var today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        var saved = send(
                owner,
                "PUT",
                SETTINGS + "?merchantId=999999",
                Map.of("openingBalance", 1000, "baseDate", today, "merchantId", 999999));
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM cash_flow_settings WHERE merchant_id=?", Integer.class, merchantId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM cash_flow_settings WHERE merchant_id=999999", Integer.class))
                .isZero();
        // 등록하지 않은 메서드는 정상 사장님 세션이어도 기본 거절이다
        problem(send(owner, "PATCH", SETTINGS, Map.of()), 403, "AUTH_002");
        problem(send(admin, "GET", SETTINGS, null), 403, "AUTH_002");
        var me = mapper.readTree(send(admin, "GET", "/api/auth/me", null).body());
        assertThat(me.path("permissions").toString()).contains("FINANCE_VIEW");
        user("staff", "SECURITY_STAFF", null, true);
        user("no_permission", "OPERATOR", merchantId, false);
        for (String suffix : List.of("staff", "no_permission")) {
            var denied = client();
            assertThat(login(denied, suffix).statusCode()).isEqualTo(200);
            problem(send(denied, "GET", SETTINGS, null), 403, "AUTH_002");
            problem(send(denied, "PUT", SETTINGS, Map.of()), 403, "AUTH_002");
        }
    }

    // 관리 API로 권한을 회수하면 기존 세션이 종료되고 재로그인 후에는 403이 된다
    @Test
    void finance_whenPermissionRevoked_expiresSessionAndDeniesNextLoginScope() throws Exception {
        assertThat(send(admin, "PUT", "/api/admin/users/" + ownerId + "/permissions", Map.of("permissions", List.of()))
                        .statusCode())
                .isEqualTo(204);
        problem(send(owner, "GET", SETTINGS, null), 401, "AUTH_001");
        assertThat(login(owner, "owner").statusCode()).isEqualTo(200);
        problem(send(owner, "GET", SETTINGS, null), 403, "AUTH_002");
    }

    // 계정 정지는 관리 API의 기존 세션 종료 흐름을 통해 업무 요청과 재로그인을 막는다
    @Test
    void finance_whenAccountDisabled_rejectsOldSessionAndLogin() throws Exception {
        assertThat(send(admin, "PUT", "/api/admin/users/" + ownerId + "/status", Map.of("status", "INACTIVE"))
                        .statusCode())
                .isEqualTo(204);
        problem(send(owner, "PUT", SETTINGS, Map.of()), 401, "AUTH_001");
        problem(login(owner, "owner"), 401, "AUTH_004");
    }

    // 가맹점 정지도 기존 세션 종료와 로그인 차단을 정산 경로에서 유지한다
    @Test
    void finance_whenMerchantInactive_rejectsOldSessionAndLogin() throws Exception {
        assertThat(send(admin, "PUT", "/api/admin/merchants/" + merchantId + "/status", Map.of("status", "INACTIVE"))
                        .statusCode())
                .isEqualTo(204);
        problem(send(owner, "GET", SETTINGS, null), 401, "AUTH_001");
        problem(login(owner, "owner"), 401, "AUTH_004");
    }

    // 새 로그인으로 만료된 쿠키와 로그아웃 쿠키는 더 이상 정산 경로를 통과하지 못한다
    @Test
    void finance_whenSessionExpiredOrLoggedOut_returnsUnauthorized() throws Exception {
        var next = client();
        assertThat(login(next, "owner").statusCode()).isEqualTo(200);
        problem(send(owner, "GET", SETTINGS, null), 401, "AUTH_001");
        reachedSettings(send(next, "GET", SETTINGS, null));
        assertThat(send(next, "POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        problem(send(next, "GET", SETTINGS, null), 401, "AUTH_001");
    }
}
