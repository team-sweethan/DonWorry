package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.api.CurrentMerchant;
import com.donworry.identity.dto.MerchantSignedRequest;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.service.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.*;

@WebMvcTest(controllers = MerchantHmacSecurityTests.ProbeController.class, properties = "security.csrf.enabled=true")
@Import({
    MerchantHmacSecurityTests.ProbeController.class,
    MerchantSecurityConfiguration.class,
    SecurityConfig.class,
    SecurityProblemResponseWriter.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    MerchantHmacService.class,
    MerchantConnectionService.class,
    com.donworry.identity.controller.MerchantConnectionController.class,
    CurrentMerchantService.class
})
class MerchantHmacSecurityTests {
    @MockitoBean
    UserDetailsService userDetailsService;

    @MockitoBean
    LoginHistoryService loginHistories;

    @MockitoBean
    MerchantRepository merchants;

    @MockitoBean
    com.donworry.identity.repository.MerchantIntegrationRepository integrations;

    @MockitoBean
    MerchantSecretService secrets;

    @MockitoBean
    MerchantNonceStore nonces;

    @MockitoBean(name = "identityClock")
    Clock clock;

    @Autowired
    MockMvc mvc;

    private final Instant now = Instant.parse("2026-10-27T06:00:00Z");
    private final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private Merchant merchant;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(now);
        merchant = mock(Merchant.class);
        when(merchant.getMerchantId()).thenReturn(7L);
        when(merchant.getMerchantCode()).thenReturn("CAKESHOP");
        when(merchant.getStatus()).thenReturn(MerchantStatus.ACTIVE);
        when(merchant.getSecretReference()).thenReturn("DONWORRY_MERCHANT_HMAC_TEST");
        when(merchants.lockById(7L)).thenReturn(Optional.of(merchant));
        var integration = mock(MerchantIntegration.class);
        when(integration.getKeyVersion()).thenReturn(1L);
        when(integration.getStatus()).thenReturn(IntegrationStatus.CONNECTED);
        when(integrations.findByMerchantId(7L)).thenReturn(Optional.of(integration));
        when(merchants.findByMerchantCode("CAKESHOP")).thenReturn(Optional.of(merchant));
        when(secrets.resolve(anyString())).thenReturn(new com.donworry.identity.dto.MerchantKeyMaterial(key, 1));
        when(nonces.consume(anyLong(), any(), any(), anyLong())).thenReturn(true);
    }

    private MockHttpServletRequestBuilder signed(String method, String target, String body, long offset) {
        String timestamp = Long.toString(now.getEpochSecond() + offset);
        String nonce = UUID.randomUUID().toString();
        String attempt = "POST".equals(method) ? "attempt-1" : "";
        var input = new MerchantSignedRequest(
                "CAKESHOP",
                timestamp,
                nonce,
                "",
                method,
                target,
                "application/json;charset=UTF-8",
                attempt,
                body.getBytes(StandardCharsets.UTF_8));
        var request = request(org.springframework.http.HttpMethod.valueOf(method), target)
                .contentType("application/json;charset=UTF-8")
                .content(body)
                .header("X-Merchant-Code", "CAKESHOP")
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", HexFormat.of().formatHex(MerchantHmacSigner.sign(key, input)));
        if (!attempt.isEmpty()) request.header("Idempotency-Key", attempt);
        return request;
    }

    // 연결 확인은 직원 세션·CSRF 없이 현재 키로만 수행하며 다른 메서드와 본문은 거절한다
    @Test
    void connection_whenSigned_enforcesEmptyPostAndMerchantChain() throws Exception {
        mvc.perform(post("/api/merchant-connections/check")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/merchant-connections/check")).andExpect(status().isMethodNotAllowed());
        mvc.perform(signed("POST", "/api/merchant-connections/check", "{}", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_007"));
        var result = mvc.perform(signed("POST", "/api/merchant-connections/check", "", 0))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    // CSRF 활성화 상태에서도 HMAC POST는 통과하고 검사한 본문을 Controller가 다시 읽는다
    @Test
    void post_whenSigned_preservesBodyWithoutSession() throws Exception {
        var result = mvc.perform(signed("POST", "/api/payments", "{\"amount\":1000}", 0))
                .andExpect(status().isOk())
                .andExpect(content().string("7:{\"amount\":1000}"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    // 직원 세션은 서명 누락을 대신하지 않고 강제 변경 상태도 가맹점 인증과 섞이지 않는다
    @Test
    void session_whenPresent_doesNotReplaceOrContaminateMerchantAuthentication() throws Exception {
        var session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                SessionUser.builder()
                        .userId(1L)
                        .role(UserRole.ADMIN)
                        .passwordChangeRequired(true)
                        .build(),
                null,
                List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(post("/api/payments").session(session).content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_001"));
        verifyNoInteractions(merchants, nonces);
        mvc.perform(signed("GET", "/api/payments/1", "", 0).session(session)).andExpect(status().isOk());
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(SessionUser.class);
    }

    // 서명된 조회 조건·본문·멱등 키를 변경하면 업무 코드에 도달하지 않는다
    @Test
    void request_whenTampered_rejectsSignedComponents() throws Exception {
        mvc.perform(signed("POST", "/api/payments", "{}", 0).content("{ }")).andExpect(status().isUnauthorized());
        mvc.perform(signed("POST", "/api/payments", "{}", 0).header("Idempotency-Key", "attempt-2"))
                .andExpect(status().isUnauthorized());
        mvc.perform(signed("GET", "/api/payment-orders?orderRef=A", "", 0).queryParam("size", "50"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(nonces);
    }

    // 과거와 미래의 5분 경계까지 허용하고 그 밖의 서명은 거절한다
    @ParameterizedTest
    @ValueSource(longs = {-301, -300, 300, 301})
    void timestamp_whenAtBoundary_enforcesWindow(long offset) throws Exception {
        mvc.perform(signed("GET", "/api/payments/1", "", offset))
                .andExpect(status().is(Math.abs(offset) <= 300 ? 200 : 401));
        if (Math.abs(offset) <= 300) verify(nonces).consume(eq(7L), any(), eq(now.plusSeconds(offset + 301)), eq(1L));
        else verifyNoInteractions(merchants, nonces);
    }

    // 이미 받은 nonce는 같은 서명으로 다시 사용할 수 없다
    @Test
    void nonce_whenReplayed_returnsUnauthorized() throws Exception {
        when(nonces.consume(anyLong(), any(), any(), anyLong())).thenReturn(true, false);
        var request = signed("GET", "/api/payments/1", "", 0);
        mvc.perform(request).andExpect(status().isOk());
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_001"));
    }

    // 유효한 서명의 정지 가맹점은 조회만 가능하고 잘못된 서명은 상태를 드러내지 않는다
    @Test
    void inactive_whenSigned_allowsGetAndRejectsPost() throws Exception {
        when(merchant.getStatus()).thenReturn(MerchantStatus.INACTIVE);
        mvc.perform(signed("GET", "/api/payments/1", "", 0)).andExpect(status().isOk());
        mvc.perform(signed("POST", "/api/payments", "{}", 0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_003"));
        mvc.perform(signed("POST", "/api/payments", "{}", 0).content("wrong")).andExpect(status().isUnauthorized());
    }

    // 정지 중 거절한 서명도 nonce를 소비해 재활성화 뒤 같은 요청으로 결제가 실행되지 않는다
    @Test
    void inactive_whenPostRejected_consumesNonceBeforeReactivation() throws Exception {
        var used = new HashSet<UUID>();
        when(nonces.consume(anyLong(), any(), any(), anyLong())).thenAnswer(call -> used.add(call.getArgument(1)));
        when(merchant.getStatus()).thenReturn(MerchantStatus.INACTIVE);
        var request = signed("POST", "/api/payments", "{}", 0);
        mvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_003"));
        when(merchant.getStatus()).thenReturn(MerchantStatus.ACTIVE);
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_001"));
    }

    // 없는 가맹점과 틀린 키는 같은 인증 오류이고 DB 장애는 503으로 구분한다
    @Test
    void authentication_whenMissingOrUnavailable_failsClosed() throws Exception {
        when(merchants.findByMerchantCode("CAKESHOP")).thenReturn(Optional.empty());
        mvc.perform(signed("GET", "/api/payments/1", "", 0)).andExpect(status().isUnauthorized());
        when(merchants.findByMerchantCode("CAKESHOP")).thenReturn(Optional.of(merchant));
        when(secrets.resolve(anyString()))
                .thenReturn(new com.donworry.identity.dto.MerchantKeyMaterial(
                        "other-key-012345678901234567890123".getBytes(StandardCharsets.UTF_8), 1));
        mvc.perform(signed("GET", "/api/payments/1", "", 0)).andExpect(status().isUnauthorized());
        when(merchants.findByMerchantCode("CAKESHOP"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        mvc.perform(signed("GET", "/api/payments/1", "", 0))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_002"));
    }

    // 자동 HEAD 매핑과 미지원 메서드가 직원 체인으로 빠져나가지 않는다
    @Test
    void methods_whenUnsupported_doNotFallThrough() throws Exception {
        mvc.perform(head("/api/payments/1")).andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/payments")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/payments")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(merchants, nonces);
    }

    // 본문 크기를 제한하고 모호한 중복 인증 헤더를 거절한다
    @Test
    void input_whenOversizedOrDuplicated_rejectsBeforeAuthentication() throws Exception {
        mvc.perform(signed("POST", "/api/payments", "x".repeat(65_537), 0)).andExpect(status().is(413));
        mvc.perform(signed("GET", "/api/payments/1", "", 0).header("X-Merchant-Code", "OTHER"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(merchants, nonces);
    }

    // 잘못된 시각·nonce·서명 형식은 DB 조회 전에 같은 401로 거절한다
    @Test
    void headers_whenMalformed_rejectBeforeDatabase() throws Exception {
        for (String name : new String[] {"X-Timestamp", "X-Nonce", "X-Signature"}) {
            var request = signed("GET", "/api/payments/1", "", 0);
            request.with(r -> {
                r.removeHeader(name);
                r.addHeader(name, "malformed");
                return r;
            });
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_001"));
        }
        verifyNoInteractions(merchants, nonces);
    }

    // nonce 저장 장애는 업무 실행 없이 503으로 끝난다
    @Test
    void nonce_whenStorageFails_returnsUnavailable() throws Exception {
        when(nonces.consume(anyLong(), any(), any(), anyLong()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        mvc.perform(signed("POST", "/api/payments", "{}", 0))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_002"));
    }

    // HMAC 헤더는 직원 인증이 아니며 예외 경로 밖의 CSRF 정책은 유지된다
    @Test
    void staffEndpoints_whenMerchantHeadersPresent_keepExistingProtection() throws Exception {
        mvc.perform(signed("GET", "/api/auth/me", "", 0)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/other-write")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/order-fulfillments/ORDER-1/other")).andExpect(status().isForbidden());
    }

    // 두 픽업 경로는 정지 가맹점도 서명과 본문을 확인하고 세션 없이 공개 신원을 제공한다
    @ParameterizedTest
    @ValueSource(strings = {"schedule", "completion"})
    void pickup_whenSigned_allowsInactiveMerchantAndPreservesBody(String action) throws Exception {
        when(merchant.getStatus()).thenReturn(MerchantStatus.INACTIVE);
        String field = "schedule".equals(action) ? "scheduledPickupAt" : "pickedUpAt";
        String body = "{\"" + field + "\":\"2026-10-27T06:00:00Z\"}";
        var result = mvc.perform(signed("PUT", "/api/v1/order-fulfillments/ORDER-1/" + action, body, 0))
                .andExpect(status().isOk())
                .andExpect(content().string("7:" + body))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        verify(nonces).consume(eq(7L), any(), any(), eq(1L));
    }

    // 직원 세션만 있거나 서명이 누락되면 픽업 수신부에 도달하지 않는다
    @ParameterizedTest
    @ValueSource(strings = {"schedule", "completion"})
    void pickup_whenOnlyStaffSession_rejectsWithoutReadingMerchant(String action) throws Exception {
        var session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                SessionUser.builder().userId(1L).role(UserRole.ADMIN).build(), null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(put("/api/v1/order-fulfillments/ORDER-1/" + action)
                        .session(session)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_001"));
        mvc.perform(signed("PUT", "/api/v1/order-fulfillments/ORDER-1/" + action, "{}", 0)
                        .session(session))
                .andExpect(status().isOk());
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(SessionUser.class);
        verify(merchants, times(1)).findByMerchantCode("CAKESHOP");
    }

    // 픽업 경로의 모든 미지원 메서드는 직원 체인 대신 405와 PUT 허용 헤더를 반환한다
    @ParameterizedTest
    @ValueSource(strings = {"schedule", "completion"})
    void pickup_whenUnsupportedMethod_returnsPutAllowHeader(String action) throws Exception {
        for (String method : List.of("GET", "HEAD", "POST", "PATCH", "DELETE", "OPTIONS")) {
            mvc.perform(request(
                            org.springframework.http.HttpMethod.valueOf(method),
                            "/api/v1/order-fulfillments/ORDER-1/" + action))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string("Allow", "PUT"));
        }
        verifyNoInteractions(merchants, nonces);
    }

    // 주문번호·픽업 동작·본문·메서드를 바꾸면 원래 서명을 재사용할 수 없다
    @Test
    void pickup_whenTampered_rejectsBeforeNonceConsumption() throws Exception {
        String path = "/api/v1/order-fulfillments/ORDER-1/schedule";
        mvc.perform(signed("PUT", path, "{}", 0).content("{ }")).andExpect(status().isUnauthorized());
        for (String target : List.of(
                "/api/v1/order-fulfillments/ORDER-2/schedule", "/api/v1/order-fulfillments/ORDER-1/completion")) {
            mvc.perform(signed("PUT", path, "{}", 0).with(r -> {
                        r.setRequestURI(target);
                        return r;
                    }))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(signed("POST", path, "{}", 0).with(r -> {
                    r.setMethod("PUT");
                    return r;
                }))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(nonces);
    }

    // 만료·재사용 서명은 거절하고 새 서명으로 같은 본문을 보내면 업무 처리에 전달한다
    @ParameterizedTest
    @ValueSource(strings = {"schedule", "completion"})
    void pickup_whenExpiredOrReplayed_rejectsButAllowsFreshRetry(String action) throws Exception {
        String path = "/api/v1/order-fulfillments/ORDER-1/" + action;
        mvc.perform(signed("PUT", path, "{}", -301)).andExpect(status().isUnauthorized());
        var used = new HashSet<UUID>();
        when(nonces.consume(anyLong(), any(), any(), anyLong())).thenAnswer(call -> used.add(call.getArgument(1)));
        var request = signed("PUT", path, "{}", 0);
        mvc.perform(request).andExpect(status().isOk());
        mvc.perform(request).andExpect(status().isUnauthorized());
        mvc.perform(signed("PUT", path, "{}", 0)).andExpect(status().isOk());
    }

    // 픽업 nonce 저장 장애는 본문 전달 없이 503으로 차단한다
    @ParameterizedTest
    @ValueSource(strings = {"schedule", "completion"})
    void pickup_whenNonceStorageFails_returnsUnavailable(String action) throws Exception {
        when(nonces.consume(anyLong(), any(), any(), anyLong()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        mvc.perform(signed("PUT", "/api/v1/order-fulfillments/ORDER-1/" + action, "{}", 0))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_002"));
    }

    @RestController
    static class ProbeController {
        private final CurrentMerchant current;

        ProbeController(CurrentMerchant current) {
            this.current = current;
        }

        @PostMapping("/api/payments")
        String post(@RequestBody String body) {
            return current.findCurrent().orElseThrow().merchantId() + ":" + body;
        }

        @PutMapping({"/api/v1/order-fulfillments/{orderId}/schedule", "/api/v1/order-fulfillments/{orderId}/completion"
        })
        String pickup(@RequestBody String body) {
            return current.findCurrent().orElseThrow().merchantId() + ":" + body;
        }

        @GetMapping({"/api/payments/{id}", "/api/payment-orders"})
        String get() {
            return current.findCurrent().orElseThrow().merchantId().toString();
        }
    }
}
