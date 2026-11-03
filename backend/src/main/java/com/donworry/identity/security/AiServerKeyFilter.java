package com.donworry.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// /internal/ai/** 의 X-Internal-Api-Key 확인 — 키가 없거나 틀리면 본문을 읽기 전에 401
// Bean으로 등록하면 모든 요청에 걸리므로 AiServerSecurityConfiguration 안에서만 만든다
final class AiServerKeyFilter extends OncePerRequestFilter {
    static final String API_KEY_HEADER = "X-Internal-Api-Key";
    static final String PRINCIPAL = "ai-server";
    private static final int MAX_KEY_LENGTH = 256;

    private final byte[] apiKey;

    AiServerKeyFilter(String apiKey) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(request)) {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, List.of()));
            SecurityContextHolder.setContext(context);
        }
        // 실패하면 인증 없이 넘겨 체인의 entryPoint가 같은 401 형식으로 답한다
        chain.doFilter(request, response);
    }

    // Spring 키 설정이 비어 있으면 전부 거절한다 — 설정을 빠뜨려도 문이 열리지 않게
    private boolean matches(HttpServletRequest request) {
        if (apiKey.length == 0) return false;
        var values = request.getHeaders(API_KEY_HEADER);
        if (values == null || !values.hasMoreElements()) return false;
        String given = values.nextElement();
        if (values.hasMoreElements() || given == null || given.length() > MAX_KEY_LENGTH) return false;
        // 응답 시간 차이로 키를 한 글자씩 알아내지 못하게 끝까지 비교한다
        return MessageDigest.isEqual(apiKey, given.getBytes(StandardCharsets.UTF_8));
    }
}
