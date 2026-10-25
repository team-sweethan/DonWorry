package com.donworry.payment.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.*;
import com.donworry.identity.api.*;
import com.donworry.payment.service.PaymentMerchantIdentity;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.*;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "payment.http.enabled=true")
@Import({MariaDbTestContainerConfig.class, PaymentHttpMariaDbTests.FakeIdentity.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentHttpMariaDbTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIdentity {
        @Bean
        PaymentMerchantIdentity fakeMerchant() {
            return () -> {
                var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
                return switch (Objects.toString(request.getHeader("X-Test-Identity"), "")) {
                    case "merchant-a" -> Optional.of(997L);
                    case "merchant-b" -> Optional.of(998L);
                    default -> Optional.empty();
                };
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    @MockitoBean
    TenantReader tenants;

    @MockitoBean
    FdsEvaluator evaluator;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setup() {
        when(tenants.findById(anyLong()))
                .thenAnswer(i -> Optional.of(new TenantSummary(i.getArgument(0), "test", "ACTIVE")));
        verdict(FdsResult.REVIEW);
    }

    private void verdict(FdsResult result) {
        doAnswer(i -> {
                    FdsEvaluationRequest r = i.getArgument(0);
                    return new FdsEvaluationResponse(
                            r.evaluationRequestId(),
                            123L,
                            result,
                            result == FdsResult.ERROR ? null : 50,
                            Instant.now(),
                            result == FdsResult.ERROR ? "INTERNAL_TEST_SECRET" : null);
                })
                .when(evaluator)
                .evaluate(any());
    }

    private String body(String order, long amount) {
        return "{\"orderRef\":\"" + order + "\",\"customerRef\":\"42\",\"amount\":" + amount + ",\"orderCreatedAt\":\""
                + Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS) + "\"}";
    }

    private HttpResponse<String> call(String method, String path, String identity, String key, String body)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (identity != null) b.header("X-Test-Identity", identity);
        if (key != null) b.header("Idempotency-Key", key);
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
    // 인증 없는 요청은 잘못된 본문보다 먼저 거절하고 업무 처리를 실행하지 않는다.
    @Test
    void post_withoutIdentity_rejectsBeforeBody() throws Exception {
        var r = call("POST", "/api/payments", null, null, "{");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("PAYMENT_AUTH_REQUIRED");
        verifyNoInteractions(evaluator);
    }
    // 같은 키 재전송·다른 내용 충돌과 가맹점 범위 조회를 실제 HTTP로 확인한다.
    @Test
    void post_andGet_preserveIdempotencyAndMerchantScope() throws Exception {
        String order = UUID.randomUUID().toString(), key = UUID.randomUUID().toString(), body = body(order, 1000);
        var first = call("POST", "/api/payments", "merchant-a", key, body);
        assertThat(first.statusCode()).isEqualTo(202);
        var json = mapper.readTree(first.body());
        long id = json.path("paymentId").asLong();
        assertThat(json.path("paymentStatus").asText()).isEqualTo("HELD");
        assertThat(first.headers().firstValue("Location")).contains("/api/payments/" + id);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.has("customerRef")).isFalse();
        assertThat(json.has("authorizationRef")).isFalse();
        var replay = call("POST", "/api/payments", "merchant-a", key, body);
        assertThat(mapper.readTree(replay.body()).path("paymentId").asLong()).isEqualTo(id);
        assertThat(call("POST", "/api/payments", "merchant-a", key, body.replace("1000", "2000"))
                        .statusCode())
                .isEqualTo(409);
        assertThat(call("GET", "/api/payments/" + id, "merchant-b", null, null).statusCode())
                .isEqualTo(404);
        assertThat(call("GET", "/api/payments/" + id + "?merchantId=997", "merchant-b", null, null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(call("GET", "/api/payments/" + id, "merchant-a", null, null).statusCode())
                .isEqualTo(200);
        var history = call("GET", "/api/payment-orders?orderRef=" + order, "merchant-a", null, null);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(history.body()).path("attempts").size()).isEqualTo(1);
        verify(evaluator).evaluate(argThat(r -> r.customerRef().equals("997:42")));
    }
    // 정지 가맹점은 POST 재전송도 금지하지만 유효 인증으로 기존 결과 조회는 허용한다.
    @Test
    void inactiveMerchant_blocksPostButAllowsRead() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(UUID.randomUUID().toString(), 1000);
        var first = call("POST", "/api/payments", "merchant-a", key, body);
        long id = mapper.readTree(first.body()).path("paymentId").asLong();
        doReturn(Optional.of(new TenantSummary(997L, "test", "INACTIVE")))
                .when(tenants)
                .findById(997L);
        assertThat(call("POST", "/api/payments", "merchant-a", key, body).statusCode())
                .isEqualTo(403);
        assertThat(call("GET", "/api/payments/" + id, "merchant-a", null, null).statusCode())
                .isEqualTo(200);
        verify(evaluator).evaluate(any());
    }
    // 입력·키·페이지 오류는 400으로 처리하며 결제를 만들지 않는다.
    @Test
    void invalidRequests_return400WithoutEvaluation() throws Exception {
        String b = body(UUID.randomUUID().toString(), 1000),
                key = UUID.randomUUID().toString();
        assertThat(call("POST", "/api/payments", "merchant-a", null, b).statusCode())
                .isEqualTo(400);
        assertThat(call("POST", "/api/payments", "merchant-a", "1-1-1-1-1", b).statusCode())
                .isEqualTo(400);
        assertThat(call("POST", "/api/payments", "merchant-a", key, b.replace("1000", "0"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(call("POST", "/api/payments", "merchant-a", key, b.replace("\"42\"", "\"9223372036854775808\""))
                        .statusCode())
                .isEqualTo(400);
        assertThat(call("GET", "/api/payments/not-a-number", "merchant-a", null, null)
                        .statusCode())
                .isEqualTo(400);
        assertThat(call("GET", "/api/payment-orders?orderRef=x&size=101", "merchant-a", null, null)
                        .statusCode())
                .isEqualTo(400);
        verifyNoInteractions(evaluator);
    }
    // 확정 거절은 정상 결과 200이며 내부 평가 오류는 응답에 노출하지 않는다.
    @Test
    void verdicts_distinguishDeclineFromPending() throws Exception {
        verdict(FdsResult.DENY);
        var denied = call(
                "POST",
                "/api/payments",
                "merchant-a",
                UUID.randomUUID().toString(),
                body(UUID.randomUUID().toString(), 1000));
        assertThat(denied.statusCode()).isEqualTo(200);
        assertThat(denied.body()).contains("FDS_REJECTED");
        verdict(FdsResult.ERROR);
        var pending = call(
                "POST",
                "/api/payments",
                "merchant-a",
                UUID.randomUUID().toString(),
                body(UUID.randomUUID().toString(), 1000));
        assertThat(pending.statusCode()).isEqualTo(202);
        assertThat(pending.body()).contains("PENDING").doesNotContain("INTERNAL_TEST_SECRET");
    }
    // 장애 응답은 내부 예외를 숨기고 같은 키 확인을 안내한다.
    @Test
    void processingFailure_returnsSafeProblem() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("INTERNAL_TEST_SECRET"))
                .when(evaluator)
                .evaluate(any());
        var r = call(
                "POST",
                "/api/payments",
                "merchant-a",
                UUID.randomUUID().toString(),
                body(UUID.randomUUID().toString(), 1000));
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).contains("PAYMENT_PROCESSING_UNAVAILABLE").doesNotContain("INTERNAL_TEST_SECRET");
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
    }
    // 내부 로직의 잘못된 인자는 고객 입력 오류로 오인하지 않고 500으로 숨긴다.
    @Test
    void internalInvalidArgument_returns500() throws Exception {
        doThrow(new IllegalArgumentException("INTERNAL_TEST_SECRET"))
                .when(evaluator)
                .evaluate(any());
        var r = call(
                "POST",
                "/api/payments",
                "merchant-a",
                UUID.randomUUID().toString(),
                body(UUID.randomUUID().toString(), 1000));
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).contains("PAYMENT_INTERNAL_ERROR").doesNotContain("INTERNAL_TEST_SECRET");
    }
}
