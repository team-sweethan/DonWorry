package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
class AuthHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    SessionRegistry sessionRegistry;

    @MockitoBean
    PlatformUserRepository repository;

    private HttpResponse<String> send(HttpClient client, String method, String path, String body) throws Exception {
        return com.donworry.HttpCsrfTestSupport.send(
                client,
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body)));
    }

    private String givenSessionUser() throws Exception {
        var user = mock(PlatformUser.class);
        given(user.getUserId()).willReturn(125L);
        given(user.getLoginId()).willReturn("session-user");
        given(user.getDisplayName()).willReturn("세션 테스트");
        given(user.getRole()).willReturn(UserRole.SECURITY_STAFF);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(user.getPasswordHash()).willReturn(encoder.encode("session-password"));
        given(repository.findByLoginId("session-user")).willReturn(Optional.of(user));
        return mapper.writeValueAsString(Map.of("loginId", "session-user", "password", "session-password"));
    }

    // 실제 서버가 발급한 쿠키로 로그인 유지와 세션 ID 교체 및 로그아웃을 검증한다
    @Test
    void authentication_whenUsingRealHttp_rotatesCookieAndInvalidatesSession() throws Exception {
        var user = mock(PlatformUser.class);
        given(user.getUserId()).willReturn(1L);
        given(user.getLoginId()).willReturn("http-admin");
        given(user.getDisplayName()).willReturn("HTTP 테스트");
        given(user.getMerchantId()).willReturn(null);
        given(user.getRole()).willReturn(UserRole.ADMIN);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(user.getPasswordHash()).willReturn(encoder.encode("http-test-password"));
        given(repository.findByLoginId("http-admin")).willReturn(Optional.of(user));

        var cookies = new CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        var body = mapper.writeValueAsString(Map.of("loginId", "http-admin", "password", "http-test-password"));

        assertThat(send(client, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
        var login = send(client, "POST", "/api/auth/login", body);
        assertThat(login.statusCode()).isEqualTo(200);
        var firstCookie = login.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID="))
                .findFirst()
                .orElseThrow();
        assertThat(firstCookie).contains("HttpOnly", "SameSite=Lax");
        var firstId = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst()
                .orElseThrow()
                .getValue();
        assertThat(send(client, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);

        var relogin = send(client, "POST", "/api/auth/login", body);
        assertThat(relogin.statusCode()).isEqualTo(200);
        var secondCookie = relogin.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID="))
                .findFirst()
                .orElseThrow();
        assertThat(secondCookie).doesNotContain(firstId);
        var secondId = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst()
                .orElseThrow()
                .getValue();
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(sessionRegistry.getSessionInformation(firstId)).isNull();
        assertThat(sessionRegistry.getAllSessions(1L, true)).hasSize(1);
        assertThat(sessionRegistry.getSessionInformation(secondId)).isNotNull();

        // GET 로그아웃은 등록하지 않은 경로라 기본 거절(403)되고 세션은 유지된다 (#392)
        assertThat(send(client, "GET", "/api/auth/logout", null).statusCode()).isEqualTo(403);
        assertThat(send(client, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        assertThat(send(client, "POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        assertThat(send(client, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(sessionRegistry.getAllSessions(1L, true)).isEmpty();
        assertThat(sessionRegistry.getSessionInformation(secondId)).isNull();
        assertThat(send(client, "POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);

        var replay = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                .header("Cookie", "JSESSIONID=" + secondId)
                .GET()
                .build();
        assertThat(HttpClient.newHttpClient()
                        .send(replay, HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(401);
    }

    // 서로 다른 쿠키 저장소의 로그인과 전체 로그아웃을 실제 서블릿 세션으로 검증한다
    @Test
    void authentication_whenTwoDevicesLoginAndLogoutAll_revokesCookiesAndCleansRegistry() throws Exception {
        var body = givenSessionUser();
        var firstCookies = new CookieManager();
        var secondCookies = new CookieManager();
        var first = HttpClient.newBuilder().cookieHandler(firstCookies).build();
        var second = HttpClient.newBuilder().cookieHandler(secondCookies).build();

        assertThat(send(first, "POST", "/api/auth/login", body).statusCode()).isEqualTo(200);
        String firstId = firstCookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst()
                .orElseThrow()
                .getValue();
        assertThat(send(second, "POST", "/api/auth/login", body).statusCode()).isEqualTo(200);
        String secondId = secondCookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst()
                .orElseThrow()
                .getValue();
        assertThat(sessionRegistry.getSessionInformation(firstId).isExpired()).isTrue();
        assertThat(sessionRegistry.getAllSessions(125L, false)).hasSize(1);

        var expired = send(first, "GET", "/api/auth/me", null);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(mapper.readTree(expired.body()).path("code").asString()).isEqualTo("AUTH_001");
        assertThat(expired.headers().firstValue("content-type").orElseThrow()).startsWith("application/problem+json");
        assertThat(expired.headers().firstValue("location")).isEmpty();
        assertThat(sessionRegistry.getSessionInformation(firstId)).isNull();
        assertThat(send(second, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);

        var logout = send(second, "POST", "/api/auth/logout-all", null);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().allValues("set-cookie"))
                .flatMap(java.net.HttpCookie::parse)
                .anySatisfy(cookie -> {
                    assertThat(cookie.getName()).isEqualTo("JSESSIONID");
                    assertThat(cookie.getValue()).isEmpty();
                    assertThat(cookie.getMaxAge()).isZero();
                });
        assertThat(sessionRegistry.getAllSessions(125L, true)).isEmpty();
        assertThat(sessionRegistry.getSessionInformation(secondId)).isNull();
        assertThat(send(second, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(send(first, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);

        for (String revokedId : java.util.List.of(firstId, secondId)) {
            var replay = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                    .header("Cookie", "JSESSIONID=" + revokedId)
                    .GET()
                    .build();
            assertThat(HttpClient.newHttpClient()
                            .send(replay, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(401);
        }
    }

    // 끊긴 세션 쿠키의 첫 요청은 로그인이라도 401로 끝나고, 세션이 정리된 다음 요청부터 로그인할 수 있다
    @Test
    void login_whenSessionCookieExpired_rejectsFirstRequestThenAllowsLogin() throws Exception {
        var body = givenSessionUser();
        var first = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var second = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertThat(send(first, "POST", "/api/auth/login", body).statusCode()).isEqualTo(200);
        assertThat(send(second, "POST", "/api/auth/login", body).statusCode()).isEqualTo(200);

        var rejected = send(first, "POST", "/api/auth/login", body);
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(mapper.readTree(rejected.body()).path("code").asString()).isEqualTo("AUTH_001");

        assertThat(send(first, "POST", "/api/auth/login", body).statusCode()).isEqualTo(200);
        assertThat(send(first, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        assertThat(send(second, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
    }

    // 실제 서버에서 등록하지 않은 경로는 기본 거절되고, 공개 경로의 오류 응답은 /error를 거쳐 그대로 나온다 (#392)
    @Test
    void defaultDeny_whenUsingRealHttp_keepsErrorResponses() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        var anonymous = send(client, "GET", "/api/security-test-missing", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).contains("AUTH_001");
        // 열어 둔 경로에서 난 오류(번호 자리에 글자)는 /error가 막히지 않아 401·403이 아니라 400이다
        assertThat(send(client, "GET", "/api/security-cases/not-a-number", null).statusCode())
                .isEqualTo(400);

        assertThat(send(client, "POST", "/api/auth/login", givenSessionUser()).statusCode())
                .isEqualTo(200);
        var authenticated = send(client, "GET", "/api/security-test-missing", null);
        assertThat(authenticated.statusCode()).isEqualTo(403);
        assertThat(authenticated.body()).contains("AUTH_002");
        // 같은 계정을 쓰는 다른 테스트에 세션을 남기지 않는다
        assertThat(send(client, "POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
    }
}
