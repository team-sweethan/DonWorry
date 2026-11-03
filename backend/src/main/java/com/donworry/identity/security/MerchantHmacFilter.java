package com.donworry.identity.security;

import com.donworry.identity.dto.MerchantSignedRequest;
import com.donworry.identity.service.MerchantHmacService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

final class MerchantHmacFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 65_536;
    private final MerchantHmacService service;
    private final SecurityProblemResponseWriter writer;
    private final Map<RequestMatcher, HttpMethod> routes;

    MerchantHmacFilter(
            MerchantHmacService service, SecurityProblemResponseWriter writer, Map<RequestMatcher, HttpMethod> routes) {
        this.service = service;
        this.writer = writer;
        this.routes = Map.copyOf(routes);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        HttpMethod method = routes.entrySet().stream()
                .filter(route -> route.getKey().matches(request))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        if (!method.matches(request.getMethod())) {
            response.setHeader("Allow", method.name());
            writer.write(response, AuthErrorCode.MERCHANT_METHOD_NOT_ALLOWED);
            return;
        }
        CachedBodyRequest cached;
        try {
            String code = header(request, "X-Merchant-Code", true);
            String timestamp = header(request, "X-Timestamp", true);
            String nonce = header(request, "X-Nonce", true);
            String signature = header(request, "X-Signature", true);
            String contentType = header(request, "Content-Type", false);
            String idempotencyKey = header(request, "Idempotency-Key", false);
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES)
                throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_BODY_TOO_LARGE);
            cached = new CachedBodyRequest(request, body);
            String target =
                    request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            var merchant = service.authenticate(MerchantSignedRequest.builder()
                    .merchantCode(code)
                    .timestamp(timestamp)
                    .nonce(nonce)
                    .signature(signature)
                    .method(request.getMethod())
                    .target(target)
                    .contentType(contentType)
                    .idempotencyKey(idempotencyKey)
                    .body(body)
                    .build());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(merchant, null, List.of()));
            SecurityContextHolder.setContext(context);
        } catch (MerchantAuthenticationException exception) {
            writer.write(response, exception.getErrorCode());
            return;
        } catch (DataAccessException | TransactionException exception) {
            // 필터 바깥으로 나온 DB 오류는 직원 인증 오류나 결제 거절로 바꾸지 않는다.
            writer.write(response, AuthErrorCode.MERCHANT_AUTHENTICATION_UNAVAILABLE);
            return;
        }
        chain.doFilter(cached, response);
    }

    private String header(HttpServletRequest request, String name, boolean required) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            if (!required) return "";
            throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED);
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value.length() > 256 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED);
        return value;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous request body only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
