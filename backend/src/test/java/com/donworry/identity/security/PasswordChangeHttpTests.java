package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.PasswordPolicy;
import com.donworry.identity.service.UserSessionService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
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
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
class PasswordChangeHttpTests {
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
    PasswordPolicy policy;

    @MockitoSpyBean
    UserSessionService sessions;

    private String loginId;
    private Long userId;
    private final String newPassword = "Changed123가!";

    @BeforeEach
    void setUp() {
        loginId = "password_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO platform_users (login_id, password_hash, display_name, status, role, created_at) VALUES (?, ?, ?, 'ACTIVE', 'ADMIN', UTC_TIMESTAMP(6))",
                loginId,
                encoder.encode("1234"),
                "비밀번호 테스트");
        userId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id = ?", Long.class, loginId);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(HttpClient client, String password) throws Exception {
        return send(
                client, "POST", "login", mapper.writeValueAsString(Map.of("loginId", loginId, "password", password)));
    }

    private HttpResponse<String> change(HttpClient client, String currentPassword, String next) throws Exception {
        return send(
                client,
                "POST",
                "password",
                mapper.writeValueAsString(Map.of("currentPassword", currentPassword, "newPassword", next)));
    }

    private String hash() {
        return jdbc.queryForObject("SELECT password_hash FROM platform_users WHERE user_id = ?", String.class, userId);
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("application/problem+json");
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("1234", newPassword, "password_hash", "$2a$");
    }

    // 세 역할 모두 변경할 수 있고 현재 쿠키는 유지되며 새 비밀번호만 로그인된다
    @ParameterizedTest
    @EnumSource(UserRole.class)
    void change_whenAuthenticated_updatesDatabaseAndPreservesCurrentSession(UserRole role) throws Exception {
        jdbc.update(
                "UPDATE platform_users SET role = ?, merchant_id = ? WHERE user_id = ?",
                role.name(),
                role == UserRole.OPERATOR
                        ? jdbc.queryForObject(
                                "SELECT merchant_id FROM merchants WHERE merchant_code = 'DEMOSHOP'", Long.class)
                        : null,
                userId);
        var current = client();
        assertThat(login(current, "1234").statusCode()).isEqualTo(200);
        String id = registry.getAllSessions(userId, false).getFirst().getSessionId();
        var result = change(current, "1234", newPassword);
        assertThat(result.statusCode()).isEqualTo(204);
        assertThat(result.body()).isEmpty();
        assertThat(result.headers().allValues("set-cookie")).isEmpty();
        assertThat(send(current, "GET", "me", null).statusCode()).isEqualTo(200);
        assertThat(registry.getSessionInformation(id).isExpired()).isFalse();
        assertThat(hash()).isNotEqualTo(newPassword);
        assertThat(encoder.matches(newPassword, hash())).isTrue();
        assertProblem(login(client(), "1234"), 401, "AUTH_004");
        assertThat(send(current, "GET", "me", null).statusCode()).isEqualTo(200);
        assertThat(login(client(), newPassword).statusCode()).isEqualTo(200);
        assertProblem(send(current, "GET", "me", null), 401, "AUTH_001");
    }

    // 여러 활성 세션을 등록해 선택 종료와 다른 사용자 보호를 검증한다
    @Test
    void change_whenOtherSessionsRegistered_expiresOnlyOtherSessionsOfSameUser() throws Exception {
        var previous = client();
        var current = client();
        assertThat(login(previous, "1234").statusCode()).isEqualTo(200);
        String previousId = registry.getAllSessions(userId, false).getFirst().getSessionId();
        assertThat(login(current, "1234").statusCode()).isEqualTo(200);
        // 실제 정책은 단일 세션이므로 선택 종료 검증용으로 이전 실세션을 재등록한다.
        registry.registerNewSession(previousId, userId);
        registry.registerNewSession("unrelated-" + loginId, 999999L);
        assertThat(change(current, "1234", newPassword).statusCode()).isEqualTo(204);
        assertProblem(send(previous, "GET", "me", null), 401, "AUTH_001");
        assertThat(send(current, "GET", "me", null).statusCode()).isEqualTo(200);
        assertThat(registry.getSessionInformation("unrelated-" + loginId).isExpired())
                .isFalse();
    }

    // 입력·비밀번호·정책 오류는 해시와 세션을 유지하고 입력값을 노출하지 않는다
    @Test
    void change_whenInvalid_returnsSafeErrorsAndPreservesPasswordAndSession() throws Exception {
        var current = client();
        assertThat(login(current, "1234").statusCode()).isEqualTo(200);
        String before = hash();
        assertProblem(change(current, "wrong", newPassword), 400, "AUTH_003");
        assertProblem(change(current, "1234", "short1"), 400, "AUTH_006");
        assertProblem(change(current, "1234", loginId), 400, "AUTH_006");
        for (String body : new String[] {"{", "{}", "{\"currentPassword\":\"1234\"}"}) {
            var response = send(current, "POST", "password", body);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain("1234", "rejectedValue", "Exception");
        }
        assertThat(send(current, "GET", "password", null).statusCode()).isEqualTo(405);
        assertThat(send(current, "GET", "me", null).statusCode()).isEqualTo(200);
        assertThat(hash()).isEqualTo(before);
        assertProblem(change(client(), "1234", newPassword), 401, "AUTH_001");
    }

    // 변경 중 DB 오류는 원인을 숨긴 500 AUTH_007이고 해시와 세션을 유지한다
    @Test
    void change_whenDatabaseFails_returnsProcessingUnavailableAndPreservesPassword() throws Exception {
        var current = client();
        assertThat(login(current, "1234").statusCode()).isEqualTo(200);
        String before = hash();
        doThrow(new DataAccessResourceFailureException("db down")).when(policy).validate(any(), any());
        var response = change(current, "1234", newPassword);
        assertProblem(response, 500, "AUTH_007");
        assertThat(response.body()).doesNotContain("db down");
        assertThat(hash()).isEqualTo(before);
        assertThat(send(current, "GET", "me", null).statusCode()).isEqualTo(200);
        verify(sessions, never()).expireAllExcept(any(), any());
    }

    // 변경 트랜잭션 중 시작된 옛 비밀번호 로그인은 커밋을 기다린 뒤 거절된다
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void login_whenPasswordChangeInProgress_waitsThenRejectsOldPassword(boolean accentAlias) throws Exception {
        var current = client();
        assertThat(login(current, "1234").statusCode()).isEqualTo(200);
        var changing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    if (loginId.equals(invocation.getArgument(0))) {
                        changing.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    }
                    return invocation.callRealMethod();
                })
                .when(policy)
                .validate(anyString(), anyString());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var change = executor.submit(() -> change(current, "1234", newPassword));
            try {
                assertThat(changing.await(10, TimeUnit.SECONDS)).isTrue();
                String attemptedId = accentAlias ? loginId.replaceFirst("a", "á") : loginId;
                assertThat(jdbc.queryForObject(
                                "SELECT user_id FROM platform_users WHERE login_id = ?", Long.class, attemptedId))
                        .isEqualTo(userId);
                var attempt = executor.submit(() -> send(
                        client(),
                        "POST",
                        "login",
                        mapper.writeValueAsString(Map.of("loginId", attemptedId, "password", "1234"))));
                assertThatThrownBy(() -> attempt.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(change.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
                assertProblem(attempt.get(10, TimeUnit.SECONDS), 401, "AUTH_004");
            } finally {
                release.countDown();
            }
        }
        assertThat(send(current, "GET", "me", null).statusCode()).isEqualTo(200);
    }

    // 옛 비밀번호 검증을 끝낸 로그인이 먼저 등록되면 이전 세션의 변경 요청은 거절된다
    @Test
    void change_whenOldPasswordLoginAlreadyVerified_doesNotReviveExpiredSession() throws Exception {
        var current = client();
        assertThat(login(current, "1234").statusCode()).isEqualTo(200);
        var verified = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    var authentication = (org.springframework.security.core.Authentication) invocation.getArgument(0);
                    if (((SessionUser) authentication.getPrincipal()).userId().equals(userId)) {
                        verified.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    }
                    return invocation.callRealMethod();
                })
                .when(sessions)
                .establishAuthentication(any(), any(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempt = executor.submit(() -> login(client(), "1234"));
            try {
                assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();
                var change = executor.submit(() -> change(current, "1234", newPassword));
                assertThatThrownBy(() -> change.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(attempt.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertProblem(change.get(10, TimeUnit.SECONDS), 401, "AUTH_001");
            } finally {
                release.countDown();
            }
        }
        assertThat(encoder.matches("1234", hash())).isTrue();
    }
}
