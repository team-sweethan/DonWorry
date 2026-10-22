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

    @MockitoBean
    PlatformUserRepository repository;

    private HttpResponse<String> send(HttpClient client, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
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

        assertThat(send(client, "GET", "/api/auth/logout", null).statusCode()).isEqualTo(404);
        assertThat(send(client, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
        assertThat(send(client, "POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        assertThat(send(client, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
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
}
