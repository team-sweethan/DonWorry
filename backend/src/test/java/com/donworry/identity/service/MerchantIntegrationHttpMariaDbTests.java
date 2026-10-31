package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.dto.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "security.csrf.enabled=false")
@Import({MariaDbTestContainerConfig.class, MerchantIntegrationHttpMariaDbTests.Keys.class})
class MerchantIntegrationHttpMariaDbTests {
    @TestConfiguration
    static class Keys {
        @Bean
        @Primary
        MerchantKeyCipher testCipher() {
            return new MerchantKeyCipher("TEST", name -> Base64.getEncoder().encodeToString(new byte[32]));
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    MerchantSecretService secrets;

    @Autowired
    MerchantHmacService hmac;

    @MockitoSpyBean
    DatabaseMerchantNonceStore nonces;

    @Autowired
    MerchantIntegrationRepository integrations;

    @Autowired
    MerchantRepository merchants;

    @MockitoSpyBean
    MerchantKeyHistoryRepository histories;

    @Autowired
    MerchantKeyCipher cipher;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    Clock identityClock;

    @Autowired
    UserSessionService sessions;

    String prefix, code;
    long merchantId, ownerId, adminId;
    Visitor owner, admin;
    static final String PASSWORD = "KeyPass123!";

    @BeforeEach
    void setup() throws Exception {
        prefix = "key" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        code = prefix.toUpperCase(Locale.ROOT);
        jdbc.update(
                "INSERT INTO merchants(merchant_code,display_name,status,pg_fee_rate,created_at) VALUES(?,'키 테스트','ACTIVE',0.01,UTC_TIMESTAMP(6))",
                code);
        merchantId = jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, code);
        ownerId = user("owner", "OPERATOR", merchantId);
        adminId = user("admin", "ADMIN", null);
        owner = new Visitor();
        admin = new Visitor();
        owner.login(prefix + "owner");
        admin.login(prefix + "admin");
    }

    @AfterEach
    void cleanup() {
        reset(histories);
        jdbc.update("DELETE FROM merchant_key_histories WHERE merchant_id=?", merchantId);
        jdbc.update("DELETE FROM merchant_integrations WHERE merchant_id=?", merchantId);
        jdbc.update("DELETE FROM merchant_request_nonces WHERE merchant_id=?", merchantId);
        for (var id : jdbc.queryForList(
                "SELECT user_id FROM platform_users WHERE login_id LIKE ?", Long.class, prefix + "%")) {
            sessions.expireAll(id);
            jdbc.update("DELETE FROM login_histories WHERE user_id=?", id);
            jdbc.update("DELETE FROM platform_users WHERE user_id=?", id);
        }
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchantId);
    }

    long user(String suffix, String role, Long merchant) {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at) VALUES(?,?,'테스트','ACTIVE',?,?,UTC_TIMESTAMP(6))",
                prefix + suffix,
                passwords.encode(PASSWORD),
                role,
                merchant);
        return jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, prefix + suffix);
    }

    String adminPath() {
        return "/api/admin/merchants/" + merchantId + "/integration";
    }

    String path(String action) {
        return "/api/owner/integration/keys" + (action.isEmpty() ? "" : "/" + action);
    }

    Map<String, Object> keyInput(long version, String password) {
        return Map.of("currentPassword", password, "expectedKeyVersion", version);
    }

    JsonNode issue() throws Exception {
        var response = owner.send("POST", path(""), keyInput(0, PASSWORD), true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return json.readTree(response.body());
    }

    int historyCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchant_key_histories WHERE merchant_id=?", Integer.class, merchantId);
    }

    void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", "encryptedSecret", "secretReference");
    }
    // 기능 권한 없는 사장님이 키를 발급·재조회하고 저장·이력·관리자 응답에는 원문이 없다
    @Test
    void issue_whenOwnerHasNoPermissions_encryptsAndRevealsOnlyToOwner() throws Exception {
        String secret = issue().path("secret").asString();
        assertThat(Base64.getDecoder().decode(secret)).hasSize(32);
        assertThat(secrets.resolve(code).key()).isEqualTo(Base64.getDecoder().decode(secret));
        var reveal = owner.send("POST", path("reveal"), keyInput(1, PASSWORD), true);
        assertThat(reveal.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reveal.body()).path("secret").asString()).isEqualTo(secret);
        assertThat(owner.send("GET", "/api/owner/integration", null, false).body())
                .contains("PENDING_CONNECTION")
                .doesNotContain(secret, "encryptedSecret");
        assertThat(admin.send("GET", adminPath(), null, false).body()).doesNotContain(secret, "encryptedSecret");
        byte[] stored = jdbc.queryForObject(
                "SELECT encrypted_secret FROM merchant_integrations WHERE merchant_id=?", byte[].class, merchantId);
        assertThat(stored).isNotEqualTo(Base64.getDecoder().decode(secret));
        assertThat(historyCount()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM merchant_key_histories WHERE merchant_id=?", merchantId)
                        .toString())
                .doesNotContain(secret);
    }
    // 재발급·폐기 후 옛 키와 검증 중이던 버전은 새 인증에 사용할 수 없다
    @Test
    void reissueAndRevoke_whenCompleted_rejectOldVersions() throws Exception {
        String first = issue().path("secret").asString();
        var changed = owner.send("POST", path("reissue"), keyInput(1, PASSWORD), true);
        assertThat(changed.statusCode()).isEqualTo(200);
        String second = json.readTree(changed.body()).path("secret").asString();
        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(() -> hmac.authenticate(signed(first))).isInstanceOf(MerchantAuthenticationException.class);
        assertThat(hmac.authenticate(signed(second)).merchantId()).isEqualTo(merchantId);
        assertThat(nonces.consume(merchantId, UUID.randomUUID(), Instant.now().plusSeconds(301), 1))
                .isFalse();
        assertThat(admin.send("POST", adminPath() + "/revoke", Map.of("reason", "유출 의심", "expectedKeyVersion", 2), true)
                        .statusCode())
                .isEqualTo(204);
        assertThatThrownBy(() -> hmac.authenticate(signed(second))).isInstanceOf(MerchantAuthenticationException.class);
        assertThat(historyCount()).isEqualTo(3);
        var next = owner.send("POST", path(""), keyInput(3, PASSWORD), true);
        assertThat(next.statusCode()).isEqualTo(200);
    }

    MerchantSignedRequest signed(String secret) {
        var input = MerchantSignedRequest.builder()
                .merchantCode(code)
                .timestamp(Long.toString(Instant.now().getEpochSecond()))
                .nonce(UUID.randomUUID().toString())
                .method("GET")
                .target("/api/payments/1")
                .contentType("")
                .idempotencyKey("")
                .body(new byte[0])
                .build();
        return MerchantSignedRequest.builder()
                .merchantCode(input.merchantCode())
                .timestamp(input.timestamp())
                .nonce(input.nonce())
                .method(input.method())
                .target(input.target())
                .contentType("")
                .idempotencyKey("")
                .body(new byte[0])
                .signature(HexFormat.of()
                        .formatHex(MerchantHmacSigner.sign(Base64.getDecoder().decode(secret), input)))
                .build();
    }
    // 연결 완료 상태는 원문 재조회가 금지되고 재발급하면 연동 전으로 돌아간다
    @Test
    void reveal_whenConnected_hidesUntilReissue() throws Exception {
        issue();
        jdbc.update(
                "UPDATE merchant_integrations SET status='CONNECTED',connected_at=UTC_TIMESTAMP(6) WHERE merchant_id=?",
                merchantId);
        problem(owner.send("POST", path("reveal"), keyInput(1, PASSWORD), true), 409, "INTEGRATION_002");
        assertThat(owner.send("POST", path("reissue"), keyInput(1, PASSWORD), true)
                        .statusCode())
                .isEqualTo(200);
        assertThat(owner.send("GET", "/api/owner/integration", null, false).body())
                .contains("PENDING_CONNECTION");
    }
    // 비밀번호 실패는 요청 롤백과 무관하게 남고 5회 뒤 올바른 비밀번호도 잠금 해제 전에는 거절한다
    @Test
    void password_whenRepeatedlyWrong_persistsLockAcrossFailures() throws Exception {
        for (int i = 0; i < 5; i++) problem(owner.send("POST", path(""), keyInput(0, "wrong"), true), 400, "AUTH_003");
        problem(owner.send("POST", path(""), keyInput(0, PASSWORD), true), 429, "INTEGRATION_003");
        assertThat(historyCount()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT login_failure_count FROM platform_users WHERE user_id=?", Integer.class, ownerId))
                .isEqualTo(5);
        jdbc.update(
                "UPDATE platform_users SET login_locked_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE user_id=?",
                ownerId);
        issue();
        assertThat(jdbc.queryForObject(
                        "SELECT login_failure_count FROM platform_users WHERE user_id=?", Integer.class, ownerId))
                .isZero();
    }
    // CSRF는 전역 off에서도 검사하며 사장님과 관리자 권한·가맹점 경계를 분리한다
    @Test
    void access_whenWrongRoleOrMissingCsrf_rejectsWithoutChanges() throws Exception {
        problem(owner.send("POST", path(""), keyInput(0, PASSWORD), false), 403, "CSRF_001");
        problem(admin.send("POST", path(""), keyInput(0, PASSWORD), true), 403, "AUTH_002");
        problem(
                owner.send("POST", adminPath() + "/revoke", Map.of("reason", "x", "expectedKeyVersion", 0), true),
                403,
                "AUTH_002");
        assertThat(new Visitor()
                        .send("GET", "/api/owner/integration", null, false)
                        .statusCode())
                .isEqualTo(401);
    }
    // 정지·강제 변경 계정은 이미 발급한 키를 화면에서 볼 수 없다
    @Test
    void access_whenAccountOrMerchantRestricted_deniesKeyOperations() throws Exception {
        issue();
        jdbc.update("UPDATE platform_users SET password_change_required=true WHERE user_id=?", ownerId);
        problem(owner.send("POST", path("reveal"), keyInput(1, PASSWORD), true), 403, "AUTH_008");
        jdbc.update(
                "UPDATE platform_users SET password_change_required=false,status='DISABLED' WHERE user_id=?", ownerId);
        problem(owner.send("POST", path("reveal"), keyInput(1, PASSWORD), true), 401, "AUTH_001");
        jdbc.update("UPDATE platform_users SET status='ACTIVE' WHERE user_id=?", ownerId);
        jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE merchant_id=?", merchantId);
        problem(owner.send("POST", path("reveal"), keyInput(1, PASSWORD), true), 403, "INTEGRATION_005");
        assertThat(admin.send("POST", adminPath() + "/revoke", Map.of("reason", "사고 대응", "expectedKeyVersion", 1), true)
                        .statusCode())
                .isEqualTo(204);
    }
    // 같은 버전으로 동시에 발급하면 하나만 키와 이력을 만든다
    @Test
    void issue_whenConcurrent_commitsOnce() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int n = 0; n < 2; n++)
                futures.add(executor.submit(() -> {
                    start.await();
                    return owner.send("POST", path(""), keyInput(0, PASSWORD), true)
                            .statusCode();
                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var f : futures) statuses.add(f.get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(historyCount()).isEqualTo(1);
    }
    // 이력 저장 실패는 발급한 키 저장도 함께 롤백한다
    @Test
    void issue_whenHistoryFails_rollsBackKey() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"))
                .when(histories)
                .save(any());
        assertThat(owner.send("POST", path(""), keyInput(0, PASSWORD), true).statusCode())
                .isEqualTo(500);
        assertThat(integrations.findByMerchantId(merchantId)).isEmpty();
        assertThat(historyCount()).isZero();
    }
    // 폐기 사유·버전이 없거나 오래됐으면 현재 키에 영향을 주지 않는다
    @Test
    void revoke_whenInvalidOrStale_preservesKey() throws Exception {
        issue();
        problem(
                admin.send("POST", adminPath() + "/revoke", Map.of("reason", " ", "expectedKeyVersion", 1), true),
                400,
                "INTEGRATION_004");
        problem(
                admin.send("POST", adminPath() + "/revoke", Map.of("reason", "확인", "expectedKeyVersion", 0), true),
                409,
                "INTEGRATION_001");
        problem(owner.send("POST", path("reissue"), keyInput(0, PASSWORD), true), 409, "INTEGRATION_001");
        assertThat(historyCount()).isEqualTo(1);
    }
    // 기존 키는 동일한 바이트로 이전하며 재기동·폐기 후 재실행으로 덮어쓰지 않는다
    @Test
    void migrate_whenRepeated_preservesKeyAndRevocation() throws Exception {
        jdbc.update(
                "UPDATE merchants SET secret_reference='DONWORRY_MERCHANT_HMAC_TEST' WHERE merchant_id=?", merchantId);
        var source = mock(MerchantLegacyKeySource.class);
        byte[] original = new byte[48];
        Arrays.fill(original, (byte) 7);
        when(source.resolve(anyString())).thenReturn(original);
        // 다른 환경 시드도 포함한 원자 이전을 검사하고 테스트 종료 시 복구한다.
        var legacy = merchants.findAllByOrderByMerchantIdAsc().stream()
                .filter(m -> m.getSecretReference() != null && m.getMerchantId() != merchantId)
                .toList();
        for (var m : legacy)
            jdbc.update("UPDATE merchants SET secret_reference=NULL WHERE merchant_id=?", m.getMerchantId());
        try {
            var migration = new MerchantKeyMigration(
                    merchants, integrations, histories, cipher, transactions, identityClock, source);
            migration.afterSingletonsInstantiated();
            assertThat(secrets.resolve(code).key()).isEqualTo(original);
            assertThat(hmac.authenticate(signed(Base64.getEncoder().encodeToString(original)))
                            .merchantId())
                    .isEqualTo(merchantId);
            assertThat(owner.send("GET", "/api/owner/integration", null, false).body())
                    .contains("CONNECTED");
            migration.afterSingletonsInstantiated();
            assertThat(historyCount()).isEqualTo(1);
            assertThat(admin.send(
                                    "POST",
                                    adminPath() + "/revoke",
                                    Map.of("reason", "테스트", "expectedKeyVersion", 1),
                                    true)
                            .statusCode())
                    .isEqualTo(204);
            // 레거시 참조가 남아 있어도 이미 폐기한 키는 다시 폐기하지 않는다.
            problem(
                    admin.send("POST", adminPath() + "/revoke", Map.of("reason", "재폐기", "expectedKeyVersion", 2), true),
                    409,
                    "INTEGRATION_001");
            migration.afterSingletonsInstantiated();
            assertThat(historyCount()).isEqualTo(2);
            assertThatThrownBy(() -> secrets.resolve(code)).isInstanceOf(MerchantAuthenticationException.class);
        } finally {
            for (var m : legacy)
                jdbc.update(
                        "UPDATE merchants SET secret_reference=? WHERE merchant_id=?",
                        m.getSecretReference(),
                        m.getMerchantId());
        }
    }

    // 서명 검증 후 폐기가 먼저 커밋되면 늦은 nonce 소비가 옛 키를 받아들이지 않는다
    @Test
    void authenticate_whenRevokedAfterVerification_rejectsBeforeNonceInsert() throws Exception {
        String secret = issue().path("secret").asString();
        var verified = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    verified.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    return call.callRealMethod();
                })
                .when(nonces)
                .consume(eq(merchantId), any(), any(), eq(1L));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> hmac.authenticate(signed(secret)));
            try {
                assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(admin.send(
                                        "POST",
                                        adminPath() + "/revoke",
                                        Map.of("reason", "동시 폐기", "expectedKeyVersion", 1),
                                        true)
                                .statusCode())
                        .isEqualTo(204);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MerchantAuthenticationException.class);
        } finally {
            reset(nonces);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_request_nonces WHERE merchant_id=?", Integer.class, merchantId))
                .isZero();
    }

    // 재발급과 폐기가 같은 버전을 보고 경합해도 한 작업만 현재 키를 변경한다
    @Test
    void reissue_whenRacingWithRevocation_commitsOneTransition() throws Exception {
        issue();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var reissue = executor.submit(() -> {
                start.await();
                return owner.send("POST", path("reissue"), keyInput(1, PASSWORD), true)
                        .statusCode();
            });
            var revoke = executor.submit(() -> {
                start.await();
                return admin.send(
                                "POST",
                                adminPath() + "/revoke",
                                Map.of("reason", "동시 폐기", "expectedKeyVersion", 1),
                                true)
                        .statusCode();
            });
            start.countDown();
            var statuses = List.of(reissue.get(20, TimeUnit.SECONDS), revoke.get(20, TimeUnit.SECONDS));
            assertThat(statuses).contains(409);
            assertThat(statuses).anyMatch(status -> status == 200 || status == 204);
        }
        assertThat(historyCount()).isEqualTo(2);
    }

    // 다른 가맹점 ID를 본문에 넣어도 세션 가맹점에만 키를 발급한다
    @Test
    void issue_whenTargetInjected_usesSessionMerchantOnly() throws Exception {
        long other =
                jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code='DEMOSHOP'", Long.class);
        var input = new HashMap<String, Object>(keyInput(0, PASSWORD));
        input.put("merchantId", other);
        var response = owner.send("POST", path(""), input, true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(historyCount()).isEqualTo(1);
        problem(owner.send("GET", "/api/admin/merchants/" + other + "/integration", null, false), 403, "AUTH_002");
    }

    // 이전 중 이력 저장이 실패하면 키도 남지 않고 실패를 호출자에게 전파한다
    @Test
    void migrate_whenHistoryFails_rollsBackAndPropagatesFailure() {
        jdbc.update(
                "UPDATE merchants SET secret_reference='DONWORRY_MERCHANT_HMAC_TEST' WHERE merchant_id=?", merchantId);
        var source = mock(MerchantLegacyKeySource.class);
        when(source.resolve(anyString())).thenReturn(new byte[32]);
        var legacy = merchants.findAllByOrderByMerchantIdAsc().stream()
                .filter(m -> m.getSecretReference() != null && m.getMerchantId() != merchantId)
                .toList();
        for (var merchant : legacy)
            jdbc.update("UPDATE merchants SET secret_reference=NULL WHERE merchant_id=?", merchant.getMerchantId());
        try {
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"))
                    .when(histories)
                    .save(any());
            var migration = new MerchantKeyMigration(
                    merchants, integrations, histories, cipher, transactions, identityClock, source);
            assertThatThrownBy(migration::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("test failure");
            assertThat(integrations.findByMerchantId(merchantId)).isEmpty();
            assertThat(historyCount()).isZero();
        } finally {
            for (var merchant : legacy)
                jdbc.update(
                        "UPDATE merchants SET secret_reference=? WHERE merchant_id=?",
                        merchant.getSecretReference(),
                        merchant.getMerchantId());
        }
    }

    class Visitor {
        final CookieManager cookies = new CookieManager();
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        void login(String login) throws Exception {
            assertThat(send("POST", "/api/auth/login", Map.of("loginId", login, "password", PASSWORD), false)
                            .statusCode())
                    .isEqualTo(200);
            send("GET", "/api/auth/csrf", null, false);
        }

        HttpResponse<String> send(String method, String path, Object body, boolean csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(
                            method,
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (csrf)
                request.header(
                        "X-XSRF-TOKEN",
                        cookies.getCookieStore().getCookies().stream()
                                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                                .findFirst()
                                .orElseThrow()
                                .getValue());
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
