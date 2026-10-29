package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.dto.AdminUserResponse;
import com.donworry.identity.dto.TemporaryPasswordResponse;
import com.donworry.identity.service.AdminChangeHistoryService;
import com.donworry.identity.service.UserSessionService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
class AdminAccountHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    SessionRegistry registry;

    @MockitoSpyBean
    AdminChangeHistoryService histories;

    @MockitoSpyBean
    UserSessionService sessions;

    private String prefix;
    private String adminLogin;
    private Long adminId;
    private Long merchantId;
    private HttpClient admin;

    @BeforeEach
    void setUp() throws Exception {
        prefix = "a129_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        adminLogin = prefix + "_admin";
        jdbc.update(
                "INSERT INTO platform_users (login_id,password_hash,display_name,status,role,created_at) VALUES (?,?,'관리자 테스트','ACTIVE','ADMIN',UTC_TIMESTAMP(6))",
                adminLogin,
                encoder.encode("AdminPass123"));
        adminId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, adminLogin);
        merchantId =
                jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code='DEMOSHOP'", Long.class);
        admin = client();
        assertThat(login(admin, adminLogin, "AdminPass123").statusCode()).isEqualTo(200);
    }

    @AfterEach
    void cleanUp() {
        // JVM 공용 컨테이너의 기존 계정·다른 테스트 데이터는 보존한다.
        var ids =
                jdbc.queryForList("SELECT user_id FROM platform_users WHERE login_id LIKE ?", Long.class, prefix + "%");
        for (Long id : ids) {
            jdbc.update("DELETE FROM login_histories WHERE user_id=?", id);
            jdbc.update("DELETE FROM admin_change_histories WHERE actor_user_id=? OR target_user_id=?", id, id);
            jdbc.update("DELETE FROM user_permissions WHERE user_id=?", id);
            sessions.expireAll(id);
        }
        for (Long id : ids) jdbc.update("DELETE FROM platform_users WHERE user_id=?", id);
        jdbc.update("DELETE FROM merchants WHERE merchant_code LIKE ?", prefix + "%");
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, Object body) throws Exception {
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

    private HttpResponse<String> login(HttpClient client, String loginId, String password) throws Exception {
        return send(client, "POST", "/api/auth/login", Map.of("loginId", loginId, "password", password));
    }

    private JsonNode create(String suffix, String role) throws Exception {
        var input = new LinkedHashMap<String, Object>();
        input.put("loginId", prefix + suffix);
        input.put("displayName", "계정 테스트");
        input.put("role", role);
        if (role.equals("OPERATOR")) input.put("merchantId", merchantId);
        var response = send(admin, "POST", "/api/admin/users", input);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        return mapper.readTree(response.body());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("passwordHash", "password_hash", "$2a$", "AdminPass123");
    }

    private int historyCount(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_change_histories WHERE target_user_id=?", Integer.class, id);
    }

    // 관리자 영역의 모든 작업은 비로그인·다른 역할을 서버에서 거절한다
    @Test
    void admin_whenNotAdmin_rejectsAllEndpoints() throws Exception {
        var created = create("_staff", "SECURITY_STAFF");
        long id = created.path("user").path("userId").asLong();
        // 강제 변경 제한과 역할 검사를 구분한다.
        jdbc.update("UPDATE platform_users SET password_change_required=FALSE WHERE user_id=?", id);
        var staff = client();
        assertThat(login(
                                staff,
                                prefix + "_staff",
                                created.path("temporaryPassword").asString())
                        .statusCode())
                .isEqualTo(200);
        for (var caller : List.of(client(), staff)) {
            int expected = caller == staff ? 403 : 401;
            String code = caller == staff ? "AUTH_002" : "AUTH_001";
            for (String path : List.of("/api/admin/users", "/api/admin/users/" + id, "/api/admin/merchants/options"))
                problem(send(caller, "GET", path, null), expected, code);
            problem(send(caller, "POST", "/api/admin/users", Map.of()), expected, code);
            problem(
                    send(caller, "PUT", "/api/admin/users/" + id + "/status", Map.of("status", "DISABLED")),
                    expected,
                    code);
            problem(
                    send(caller, "PUT", "/api/admin/users/" + id + "/permissions", Map.of("permissions", List.of())),
                    expected,
                    code);
            problem(send(caller, "POST", "/api/admin/users/" + id + "/password-reset", null), expected, code);
        }
    }

    // 생성 규칙·중복·조회 계약과 비밀정보 일회 응답을 실제 DB로 검증한다
    @Test
    void create_whenValid_returnsSecretOnceAndRejectsInvalidAccounts() throws Exception {
        var created = create("_owner", "OPERATOR");
        long id = created.path("user").path("userId").asLong();
        String password = created.path("temporaryPassword").asString();
        assertThat(created.path("user").path("passwordChangeRequired").asBoolean())
                .isTrue();
        String hash = jdbc.queryForObject("SELECT password_hash FROM platform_users WHERE user_id=?", String.class, id);
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(historyCount(id)).isOne();
        var detail = send(admin, "GET", "/api/admin/users/" + id, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).doesNotContain(password, hash, "temporaryPassword", "passwordHash");
        var list = send(admin, "GET", "/api/admin/users?keyword=" + prefix + "&role=OPERATOR&size=1", null);
        assertThat(mapper.readTree(list.body()).path("totalElements").asLong()).isOne();
        assertThat(mapper.readTree(list.body()).path("items").size()).isOne();
        // 방금 만든 계정은 임시 비밀번호 상태라 변경 필요 조건에만 걸린다.
        var pending =
                send(admin, "GET", "/api/admin/users?keyword=" + prefix + "_owner&passwordChangeRequired=true", null);
        assertThat(mapper.readTree(pending.body()).path("totalElements").asLong())
                .isOne();
        var changed =
                send(admin, "GET", "/api/admin/users?keyword=" + prefix + "_owner&passwordChangeRequired=false", null);
        assertThat(mapper.readTree(changed.body()).path("totalElements").asLong())
                .isZero();
        problem(send(admin, "GET", "/api/admin/users?passwordChangeRequired=maybe", null), 400, "ADMIN_001");
        problem(send(admin, "GET", "/api/admin/users?size=101", null), 400, "ADMIN_001");
        problem(send(admin, "GET", "/api/admin/users?role=UNKNOWN", null), 400, "ADMIN_001");
        problem(send(admin, "GET", "/api/admin/users?page=abc", null), 400, "ADMIN_001");
        problem(send(admin, "GET", "/api/admin/users/abc", null), 400, "ADMIN_001");
        problem(send(admin, "GET", "/api/admin/users/99999999", null), 404, "ADMIN_002");
        for (String role : List.of("ADMIN", "UNKNOWN"))
            problem(
                    send(
                            admin,
                            "POST",
                            "/api/admin/users",
                            Map.of("loginId", prefix, "displayName", "이름", "role", role)),
                    400,
                    "ADMIN_001");
        for (String invalid : List.of("ab", "UPPER", "abc-d", "a".repeat(31)))
            problem(
                    send(
                            admin,
                            "POST",
                            "/api/admin/users",
                            Map.of("loginId", invalid, "displayName", "이름", "role", "SECURITY_STAFF")),
                    400,
                    "ADMIN_001");
        problem(
                send(
                        admin,
                        "POST",
                        "/api/admin/users",
                        Map.of("loginId", prefix, "displayName", "이름", "role", "OPERATOR")),
                400,
                "ADMIN_005");
        problem(
                send(
                        admin,
                        "POST",
                        "/api/admin/users",
                        Map.of(
                                "loginId",
                                prefix,
                                "displayName",
                                "이름",
                                "role",
                                "SECURITY_STAFF",
                                "merchantId",
                                merchantId)),
                400,
                "ADMIN_001");
        problem(
                send(
                        admin,
                        "POST",
                        "/api/admin/users",
                        Map.of(
                                "loginId",
                                prefix + "_owner",
                                "displayName",
                                "이름",
                                "role",
                                "OPERATOR",
                                "merchantId",
                                merchantId)),
                409,
                "ADMIN_003");
        String audit = jdbc.queryForObject(
                "SELECT after_data FROM admin_change_histories WHERE target_user_id=?", String.class, id);
        assertThat(audit).doesNotContain(password, hash, "passwordHash", "temporaryPassword");
        assertThat(new TemporaryPasswordResponse(
                                AdminUserResponse.builder().userId(id).build(), password)
                        .toString())
                .doesNotContain(password);
    }

    // 임시 비밀번호 계정은 직접 API도 막히며 변경 후 현재 세션을 유지하고 정상 상태를 조회한다
    @Test
    void forcedChange_whenPasswordChanged_updatesDatabaseAndCurrentSession() throws Exception {
        var created = create("_forced", "SECURITY_STAFF");
        long id = created.path("user").path("userId").asLong();
        var caller = client();
        var login = login(
                caller, prefix + "_forced", created.path("temporaryPassword").asString());
        assertThat(mapper.readTree(login.body()).path("passwordChangeRequired").asBoolean())
                .isTrue();
        String sessionId = registry.getAllSessions(id, false).getFirst().getSessionId();
        problem(send(caller, "GET", "/api/fds/evaluations", null), 403, "AUTH_008");
        String temporary = created.path("temporaryPassword").asString();
        problem(
                send(
                        caller,
                        "POST",
                        "/api/auth/password",
                        Map.of("currentPassword", temporary, "newPassword", temporary)),
                400,
                "AUTH_009");
        var changed = send(
                caller,
                "POST",
                "/api/auth/password",
                Map.of(
                        "currentPassword",
                        created.path("temporaryPassword").asString(),
                        "newPassword",
                        "NewAccount123"));
        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(changed.headers().allValues("set-cookie")).isEmpty();
        var me = send(caller, "GET", "/api/auth/me", null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).path("passwordChangeRequired").asBoolean())
                .isFalse();
        assertThat(registry.getSessionInformation(sessionId).isExpired()).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT password_change_required FROM platform_users WHERE user_id=?", Boolean.class, id))
                .isFalse();
        assertThat(send(caller, "GET", "/api/fds/evaluations", null).statusCode())
                .isEqualTo(200);
        assertThat(login(client(), prefix + "_forced", "NewAccount123").statusCode())
                .isEqualTo(200);
    }

    // 권한·정지·초기화는 이력을 남기고 세션을 종료하며 같은 값은 반복 반영하지 않는다
    @Test
    void mutations_whenCommitted_expireSessionAndRecordOnlyActualChanges() throws Exception {
        var created = create("_mutate", "OPERATOR");
        long id = created.path("user").path("userId").asLong();
        String base = "/api/admin/users/" + id;
        String password = created.path("temporaryPassword").asString();
        var caller = client();
        assertThat(login(caller, prefix + "_mutate", password).statusCode()).isEqualTo(200);
        assertThat(send(admin, "PUT", base + "/permissions", Map.of("permissions", List.of("FDS_VIEW", "FINANCE_VIEW")))
                        .statusCode())
                .isEqualTo(204);
        problem(send(caller, "GET", "/api/auth/me", null), 401, "AUTH_001");
        assertThat(historyCount(id)).isEqualTo(2);
        assertThat(send(admin, "PUT", base + "/permissions", Map.of("permissions", List.of("FINANCE_VIEW", "FDS_VIEW")))
                        .statusCode())
                .isEqualTo(204);
        assertThat(historyCount(id)).isEqualTo(2);
        assertThat(send(admin, "PUT", base + "/permissions", Map.of("permissions", List.of("FINANCE_VIEW")))
                        .statusCode())
                .isEqualTo(204);
        assertThat(jdbc.queryForList("SELECT permission_code FROM user_permissions WHERE user_id=?", String.class, id))
                .containsExactly("FINANCE_VIEW");
        // 목록은 가맹점 이름·권한을 모아 읽어도 계정별로 정확히 붙인다
        var row = mapper.readTree(send(admin, "GET", "/api/admin/users?keyword=" + prefix + "_mutate", null)
                        .body())
                .path("items")
                .get(0);
        assertThat(row.path("permissions").get(0).asString()).isEqualTo("FINANCE_VIEW");
        assertThat(row.path("permissions").size()).isEqualTo(1);
        assertThat(row.path("merchantName").isNull()).isFalse();
        caller = client();
        assertThat(login(caller, prefix + "_mutate", password).statusCode()).isEqualTo(200);
        assertThat(send(admin, "PUT", base + "/status", Map.of("status", "DISABLED"))
                        .statusCode())
                .isEqualTo(204);
        problem(send(caller, "GET", "/api/auth/me", null), 401, "AUTH_001");
        problem(login(client(), prefix + "_mutate", password), 401, "AUTH_004");
        assertThat(send(admin, "PUT", base + "/status", Map.of("status", "ACTIVE"))
                        .statusCode())
                .isEqualTo(204);
        caller = client();
        assertThat(login(caller, prefix + "_mutate", password).statusCode()).isEqualTo(200);
        var reset = send(admin, "POST", base + "/password-reset", null);
        assertThat(reset.statusCode()).isEqualTo(200);
        assertThat(reset.headers().firstValue("cache-control")).contains("no-store");
        problem(send(caller, "GET", "/api/auth/me", null), 401, "AUTH_001");
        problem(login(client(), prefix + "_mutate", password), 401, "AUTH_004");
        assertThat(login(
                                client(),
                                prefix + "_mutate",
                                mapper.readTree(reset.body())
                                        .path("temporaryPassword")
                                        .asString())
                        .statusCode())
                .isEqualTo(200);
        assertThat(historyCount(id)).isEqualTo(6);
        problem(
                send(admin, "PUT", "/api/admin/users/" + adminId + "/status", Map.of("status", "DISABLED")),
                400,
                "ADMIN_004");
        problem(
                send(admin, "PUT", "/api/admin/users/" + adminId + "/permissions", Map.of("permissions", List.of())),
                400,
                "ADMIN_006");
    }

    // 이력 저장 실패는 계정 변경을 롤백하고 기존 세션도 보존한다
    @Test
    void mutation_whenAuditFails_rollsBackAndPreservesSessions() throws Exception {
        var created = create("_rollback", "SECURITY_STAFF");
        long id = created.path("user").path("userId").asLong();
        var caller = client();
        assertThat(login(
                                caller,
                                prefix + "_rollback",
                                created.path("temporaryPassword").asString())
                        .statusCode())
                .isEqualTo(200);
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when((com.donworry.identity.service.AdminChangeHistoryService)
                        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(histories))
                .recordUser(anyLong(), anyLong(), anyString(), any(), any());
        problem(
                send(admin, "PUT", "/api/admin/users/" + id + "/status", Map.of("status", "DISABLED")),
                500,
                "AUTH_007");
        assertThat(jdbc.queryForObject("SELECT status FROM platform_users WHERE user_id=?", String.class, id))
                .isEqualTo("ACTIVE");
        assertThat(historyCount(id)).isOne();
        assertThat(send(caller, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        String hash = jdbc.queryForObject("SELECT password_hash FROM platform_users WHERE user_id=?", String.class, id);
        problem(send(admin, "POST", "/api/admin/users/" + id + "/password-reset", null), 500, "AUTH_007");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM platform_users WHERE user_id=?", String.class, id))
                .isEqualTo(hash);
        assertThat(send(caller, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        problem(
                send(
                        admin,
                        "POST",
                        "/api/admin/users",
                        Map.of("loginId", prefix + "_fail", "displayName", "이름", "role", "SECURITY_STAFF")),
                500,
                "AUTH_007");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_users WHERE login_id=?", Integer.class, prefix + "_fail"))
                .isZero();
    }

    // 가맹점 선택 조회는 정지 가맹점을 제외하고 서버도 생성 연결을 거절한다
    @Test
    void create_whenMerchantInactive_rejectsAndExcludesFromOptions() throws Exception {
        jdbc.update(
                "INSERT INTO merchants(merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,'INACTIVE',0.020000,UTC_TIMESTAMP(6))",
                prefix + "_m",
                prefix);
        long inactive = jdbc.queryForObject(
                "SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, prefix + "_m");
        problem(
                send(
                        admin,
                        "POST",
                        "/api/admin/users",
                        Map.of("loginId", prefix, "displayName", "이름", "role", "OPERATOR", "merchantId", inactive)),
                400,
                "ADMIN_005");
        var options = send(admin, "GET", "/api/admin/merchants/options?keyword=" + prefix, null);
        assertThat(options.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(options.body()).size()).isZero();
    }

    // 권한 이력 저장 실패면 삭제와 새 권한 추가를 모두 롤백한다
    @Test
    void permissions_whenAuditFails_preservesOriginalPermissionsAndSession() throws Exception {
        var created = create("_perfail", "OPERATOR");
        long id = created.path("user").path("userId").asLong();
        String path = "/api/admin/users/" + id + "/permissions";
        assertThat(send(admin, "PUT", path, Map.of("permissions", List.of("FDS_VIEW")))
                        .statusCode())
                .isEqualTo(204);
        var caller = client();
        assertThat(login(
                                caller,
                                prefix + "_perfail",
                                created.path("temporaryPassword").asString())
                        .statusCode())
                .isEqualTo(200);
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when((com.donworry.identity.service.AdminChangeHistoryService)
                        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(histories))
                .recordUser(anyLong(), anyLong(), anyString(), any(), any());
        problem(send(admin, "PUT", path, Map.of("permissions", List.of("FINANCE_VIEW"))), 500, "AUTH_007");
        assertThat(jdbc.queryForList("SELECT permission_code FROM user_permissions WHERE user_id=?", String.class, id))
                .containsExactly("FDS_VIEW");
        assertThat(historyCount(id)).isEqualTo(2);
        assertThat(send(caller, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
    }

    // 관리자가 자기 비밀번호를 초기화해도 성공 응답을 먼저 받고 다음 요청부터 만료된다
    @Test
    void reset_whenSelf_returnsSecretAndExpiresOwnSession() throws Exception {
        var response = send(admin, "POST", "/api/admin/users/" + adminId + "/password-reset", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("temporaryPassword").asString())
                .isNotBlank();
        problem(send(admin, "GET", "/api/auth/me", null), 401, "AUTH_001");
        var renewed = login(
                client(),
                adminLogin,
                mapper.readTree(response.body()).path("temporaryPassword").asString());
        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(renewed.body())
                        .path("passwordChangeRequired")
                        .asBoolean())
                .isTrue();
    }

    // 옛 상태의 로그인 등록이 먼저 시작되어도 관리자 변경 완료 뒤에는 해당 세션이 남지 않는다
    @ParameterizedTest
    @ValueSource(strings = {"status", "permissions", "password-reset"})
    void mutation_whenLoginAlreadyVerified_expiresThatSessionAfterCommit(String operation) throws Exception {
        var created = create("_first", "OPERATOR");
        long id = created.path("user").path("userId").asLong();
        var verified = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    var authentication = (org.springframework.security.core.Authentication) invocation.getArgument(0);
                    if (((SessionUser) authentication.getPrincipal()).userId().equals(id)) {
                        verified.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    }
                    return invocation.callRealMethod();
                })
                .when(sessions)
                .establishAuthentication(any(), any(), any());
        var caller = client();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var loggingIn = executor.submit(() -> login(
                    caller, prefix + "_first", created.path("temporaryPassword").asString()));
            try {
                assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();
                Object body = switch (operation) {
                    case "status" -> Map.of("status", "DISABLED");
                    case "permissions" -> Map.of("permissions", List.of("FDS_VIEW"));
                    default -> null;
                };
                var mutation = executor.submit(() -> send(
                        admin,
                        operation.equals("password-reset") ? "POST" : "PUT",
                        "/api/admin/users/" + id + "/" + operation,
                        body));
                assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(loggingIn.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(mutation.get(10, TimeUnit.SECONDS).statusCode())
                        .isEqualTo(operation.equals("password-reset") ? 200 : 204);
                problem(send(caller, "GET", "/api/auth/me", null), 401, "AUTH_001");
            } finally {
                release.countDown();
            }
        }
    }

    // DB가 역할·가맹점 연결과 이력 대상의 NULL 조합까지 검사한다
    @Test
    void constraints_whenInvalid_rejectRoleMerchantAndAuditTargets() {
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE platform_users SET merchant_id=? WHERE user_id=?", merchantId, adminId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE platform_users SET role='OPERATOR' WHERE user_id=?", adminId))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (var pair : new Long[][] {{null, null}, {adminId, merchantId}})
            assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO admin_change_histories(actor_user_id,target_user_id,target_merchant_id,change_type,created_at) VALUES (?,?,?,'TEST',UTC_TIMESTAMP(6))",
                            adminId,
                            pair[0],
                            pair[1]))
                    .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 변경 중 로그인은 커밋·세션 종료를 기다린 뒤 새 상태로 인증한다
    @ParameterizedTest
    @ValueSource(strings = {"status", "permissions", "password-reset"})
    void login_whenAdminMutationInProgress_waitsAndUsesCommittedState(String operation) throws Exception {
        var created = create("_race", "OPERATOR");
        long id = created.path("user").path("userId").asLong();
        var changing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    changing.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return invocation.callRealMethod();
                })
                .when((com.donworry.identity.service.AdminChangeHistoryService)
                        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(histories))
                .recordUser(anyLong(), anyLong(), anyString(), any(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Object body = switch (operation) {
                case "status" -> Map.of("status", "DISABLED");
                case "permissions" -> Map.of("permissions", List.of("FDS_VIEW"));
                default -> null;
            };
            var mutation = executor.submit(() -> send(
                    admin,
                    operation.equals("password-reset") ? "POST" : "PUT",
                    "/api/admin/users/" + id + "/" + operation,
                    body));
            try {
                assertThat(changing.await(10, TimeUnit.SECONDS)).isTrue();
                var login = executor.submit(() -> login(
                        client(),
                        (prefix + "_race").replaceFirst("a", "á"),
                        created.path("temporaryPassword").asString()));
                assertThatThrownBy(() -> login.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(mutation.get(10, TimeUnit.SECONDS).statusCode())
                        .isEqualTo(operation.equals("password-reset") ? 200 : 204);
                var response = login.get(10, TimeUnit.SECONDS);
                if (operation.equals("permissions")) {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(mapper.readTree(response.body())
                                    .path("permissions")
                                    .toString())
                            .contains("FDS_VIEW");
                } else problem(response, 401, "AUTH_004");
            } finally {
                release.countDown();
            }
        }
    }
}
