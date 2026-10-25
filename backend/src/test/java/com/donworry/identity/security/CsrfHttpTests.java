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
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "security.csrf.enabled=true")
@Import(MariaDbTestContainerConfig.class)
class CsrfHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    PasswordEncoder encoder;

    @MockitoBean
    PlatformUserRepository repository;

    private HttpResponse<String> send(HttpClient client, String method, String path, String token, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("X-XSRF-TOKEN", token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN") && !cookie.hasExpired())
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    // 실제 쿠키 저장소에서 로그인·로그아웃의 폐기와 새 토큰, 옛 헤더 거부를 확인한다.
    @Test
    void csrf_whenUsingRealHttp_rotatesCookiesAndPreservesSessionRules() throws Exception {
        var user = mock(PlatformUser.class);
        given(user.getUserId()).willReturn(126L);
        given(user.getLoginId()).willReturn("csrf-http");
        given(user.getDisplayName()).willReturn("CSRF HTTP");
        given(user.getRole()).willReturn(UserRole.ADMIN);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(user.getPasswordHash()).willReturn(encoder.encode("test-password"));
        given(repository.findByLoginId("csrf-http")).willReturn(Optional.of(user));
        String loginBody = "{\"loginId\":\"csrf-http\",\"password\":\"test-password\"}";

        for (var logoutPath : new String[] {"/api/auth/logout", "/api/auth/logout-all"}) {
            var cookies = new CookieManager();
            var client = HttpClient.newBuilder().cookieHandler(cookies).build();
            var issued = send(client, "GET", "/api/auth/csrf", null, null);
            assertThat(issued.statusCode()).isEqualTo(204);
            assertThat(issued.headers().firstValue("cache-control")).contains("no-store");
            assertThat(cookies.getCookieStore().getCookies())
                    .noneMatch(cookie -> cookie.getName().equals("JSESSIONID"));
            var before = token(cookies);
            var login = send(client, "POST", "/api/auth/login", before, loginBody);
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(cookies.getCookieStore().getCookies())
                    .noneMatch(cookie -> cookie.getName().equals("XSRF-TOKEN"));
            assertThat(send(client, "GET", "/api/auth/csrf", null, null).statusCode())
                    .isEqualTo(204);
            var after = token(cookies);
            assertThat(after).isNotEqualTo(before);
            var rejected = send(client, "POST", logoutPath, before, null);
            assertThat(rejected.statusCode()).isEqualTo(403);
            assertThat(rejected.body()).contains("CSRF_001");
            assertThat(send(client, "GET", "/api/auth/me", null, null).statusCode())
                    .isEqualTo(200);
            assertThat(send(client, "POST", logoutPath, after, null).statusCode())
                    .isEqualTo(204);
            assertThat(cookies.getCookieStore().getCookies())
                    .noneMatch(cookie -> cookie.getName().equals("XSRF-TOKEN")
                            || cookie.getName().equals("JSESSIONID"));
            assertThat(send(client, "GET", "/api/auth/csrf", null, null).statusCode())
                    .isEqualTo(204);
            assertThat(token(cookies)).isNotEqualTo(after);
            assertThat(send(client, "GET", "/api/auth/me", null, null).statusCode())
                    .isEqualTo(401);
        }
    }
}
