package com.donworry;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

// 실제 HTTP 업무 테스트도 브라우저처럼 현재 쿠키의 토큰을 전송한다. 업무 요청은 재시도하지 않는다.
public final class HttpCsrfTestSupport {
    private HttpCsrfTestSupport() {}

    public static HttpRequest.Builder withCsrf(HttpClient client, HttpRequest.Builder builder) throws Exception {
        var request = builder.build();
        if (Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.method())) return builder;
        var cookies = (CookieManager) client.cookieHandler().orElseThrow();
        var token = token(cookies);
        if (token == null) {
            var uri = request.uri();
            var response = client.send(
                    HttpRequest.newBuilder(new URI(uri.getScheme(), uri.getAuthority(), "/api/auth/csrf", null, null))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(204);
            token = token(cookies);
        }
        assertThat(token).isNotNull();
        return builder.header("X-XSRF-TOKEN", token);
    }

    public static HttpResponse<String> send(HttpClient client, HttpRequest.Builder builder) throws Exception {
        var request = withCsrf(client, builder).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // 성공 로그인은 토큰을 폐기한다. 다음 요청 전에 갱신해 만료 세션 검사에 선행 GET이 끼지 않게 한다.
        if (request.uri().getPath().equals("/api/auth/login") && response.statusCode() == 200) {
            var uri = request.uri();
            var issued = client.send(
                    HttpRequest.newBuilder(new URI(uri.getScheme(), uri.getAuthority(), "/api/auth/csrf", null, null))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(issued.statusCode()).isEqualTo(204);
        }
        return response;
    }

    private static String token(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN") && !cookie.hasExpired())
                .map(java.net.HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
