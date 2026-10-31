package com.donworry.payment.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.*;
import com.donworry.identity.api.*;
import com.donworry.identity.dto.MerchantSignedRequest;
import com.donworry.identity.service.MerchantHmacSigner;
import com.donworry.identity.service.MerchantSecretService;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "payment.http.enabled=true")
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentHttpMariaDbTests {
    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    @MockitoBean
    MerchantSecretService secrets;

    @Autowired
    JdbcTemplate jdbc;

    private final byte[] signingKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @MockitoBean
    FdsEvaluator evaluator;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setup() {
        for (long id : new long[] {997, 998}) {
            jdbc.update(
                    "INSERT INTO merchants (merchant_id, merchant_code, display_name, status, pg_fee_rate, secret_reference, created_at) "
                            + "VALUES (?, ?, 'HMAC test', 'ACTIVE', 0.01, 'DONWORRY_MERCHANT_HMAC_TEST', UTC_TIMESTAMP(6)) "
                            + "ON DUPLICATE KEY UPDATE status='ACTIVE'",
                    id,
                    "HMAC" + id);
            com.donworry.identity.service.MerchantIntegrationTestFixtures.installKey(jdbc, id, signingKey);
        }
        when(secrets.resolve(anyString())).thenReturn(new com.donworry.identity.dto.MerchantKeyMaterial(signingKey, 1));
        verdict(FdsResult.REVIEW);
    }

    @AfterEach
    void cleanUpMerchants() {
        jdbc.update("DELETE FROM merchant_request_nonces WHERE merchant_id IN (997,998)");
        jdbc.update("DELETE FROM merchant_integrations WHERE merchant_id IN (997,998)");
        jdbc.update("DELETE FROM merchants WHERE merchant_code IN ('HMAC997','HMAC998')");
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
        return client.send(signedRequest(method, path, identity, key, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest signedRequest(String method, String path, String identity, String key, String body) {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (identity != null) {
            String merchantCode = identity.equals("merchant-a") ? "HMAC997" : "HMAC998";
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            var input = new MerchantSignedRequest(
                    merchantCode,
                    timestamp,
                    nonce,
                    "",
                    method,
                    path,
                    "application/json",
                    key == null ? "" : key,
                    body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
            b.header("X-Merchant-Code", merchantCode)
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", HexFormat.of().formatHex(MerchantHmacSigner.sign(signingKey, input)));
        }
        if (key != null) b.header("Idempotency-Key", key);
        return b.method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
    // 인증 없는 요청은 잘못된 본문보다 먼저 거절하고 업무 처리를 실행하지 않는다.
    @Test
    void post_withoutIdentity_rejectsBeforeBody() throws Exception {
        var r = call("POST", "/api/payments", null, null, "{");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("MERCHANT_AUTH_001");
        verifyNoInteractions(evaluator);
    }

    // 같은 HTTP 서명은 거절하되 새 nonce와 같은 결제 키로 재시도하면 기존 결과를 돌려준다
    @Test
    void post_whenSignatureReplayed_rejectsButAllowsResignedRetry() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = body(UUID.randomUUID().toString(), 1000);
        var request = signedRequest("POST", "/api/payments", "merchant-a", key, body);
        var first = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(202);
        var duplicate = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(duplicate.statusCode()).isEqualTo(401);
        assertThat(duplicate.body()).contains("MERCHANT_AUTH_001");
        var retry = call("POST", "/api/payments", "merchant-a", key, body);
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(mapper.readTree(retry.body()).path("paymentId").asLong())
                .isEqualTo(mapper.readTree(first.body()).path("paymentId").asLong());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_request_nonces WHERE merchant_id=997", Integer.class))
                .isEqualTo(2);
        verify(evaluator).evaluate(any());
    }

    // 서명 확인 후 업무 입력이 거절돼도 소비한 nonce를 되살리지 않는다
    @Test
    void post_whenBodyRejected_preservesConsumedNonce() throws Exception {
        var request = signedRequest(
                "POST", "/api/payments", "merchant-a", UUID.randomUUID().toString(), "{");
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(400);
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
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
        jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE merchant_id=997");
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
    // FDS 식별자는 P2 평가 요청에 전달하고 가맹점 응답에는 되돌려 주지 않는다. 카드 원문 · 잘못된 형식은 400 (#325).
    @Test
    void post_withRiskSignals_passesThemToFdsWithoutEcho() throws Exception {
        verdict(FdsResult.ALLOW);
        String base = body(UUID.randomUUID().toString(), 1000);
        String body = base.substring(0, base.length() - 1)
                + ",\"deviceId\":\"device-1\",\"cardToken\":\"token-1\",\"cardDisplayLabel\":\"신한 **** 1234\""
                + ",\"ipCountryCode\":\"kr\",\"ipAddress\":\"203.0.113.10\",\"buyerEmail\":\"Buyer@Example.com\"}";
        var response =
                call("POST", "/api/payments", "merchant-a", UUID.randomUUID().toString(), body);
        assertThat(response.statusCode()).isIn(200, 202);
        assertThat(response.body())
                .doesNotContain("device-1", "token-1", "203.0.113.10", "buyer@example.com", "**** 1234");
        verify(evaluator)
                .evaluate(argThat(r -> "device-1".equals(r.deviceId())
                        && "token-1".equals(r.cardToken())
                        && "신한 **** 1234".equals(r.cardDisplayLabel())
                        && "KR".equals(r.ipCountryCode())
                        && "203.0.113.10".equals(r.ipAddress())
                        && "buyer@example.com".equals(r.buyerEmail())));

        for (var invalid : new String[] {
            ",\"cardDisplayLabel\":\"4111 1111 1111 1111\"}",
            ",\"ipAddress\":\"localhost\"}",
            ",\"ipCountryCode\":\"KOR\"}",
            ",\"buyerEmail\":\"not-email\"}"
        }) {
            String other = body(UUID.randomUUID().toString(), 1000);
            assertThat(call(
                                    "POST",
                                    "/api/payments",
                                    "merchant-a",
                                    UUID.randomUUID().toString(),
                                    other.substring(0, other.length() - 1) + invalid)
                            .statusCode())
                    .as(invalid)
                    .isEqualTo(400);
        }
        verify(evaluator).evaluate(any());
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

    @Autowired
    com.donworry.payment.repository.PaymentRepository cancellationPayments;

    @Autowired
    com.donworry.payment.repository.PaymentOrderRepository cancellationOrders;

    @Autowired
    com.donworry.payment.repository.PaymentRequestRepository cancellationOriginals;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    private long pendingCancellationPayment() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
            var now = Instant.now();
            var ref = UUID.randomUUID().toString();
            var order = cancellationOrders.save(
                    com.donworry.payment.entity.PaymentOrder.register(997L, ref, "customer", 100L, now, now));
            var payment = cancellationPayments.save(
                    com.donworry.payment.entity.Payment.pending(997L, "customer", ref, 100L, now));
            cancellationOriginals.save(com.donworry.payment.entity.PaymentRequest.received(
                    order, payment, order.allocateAttempt(now), UUID.randomUUID(), UUID.randomUUID()));
            return payment.getPaymentId();
        });
    }

    // 일시 거절도 커밋하고 새 서명의 같은 요청 및 조회에서 원래 기록을 반환한다.
    @Test
    void cancellation_whenPending_commitsConflictAndSupportsSignedReplay() throws Exception {
        long id = pendingCancellationPayment();
        String path = "/api/payments/" + id + "/cancellations";
        String key = UUID.randomUUID().toString();
        String body = "{\"reason\":\"고객 취소\"}";
        var signed = signedRequest("POST", path, "merchant-a", key, body);
        var first = client.send(signed, HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(409);
        assertThat(first.body()).contains("PAYMENT_IN_PROGRESS", "DEFERRED", key);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        String location = "/api/payment-cancellation-requests/" + key;
        assertThat(first.headers().firstValue("Location")).contains(location);
        var duplicate = client.send(signed, HttpResponse.BodyHandlers.ofString());
        assertThat(duplicate.statusCode()).isEqualTo(401);
        assertThat(duplicate.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(call("POST", path, "merchant-a", key, body).statusCode()).isEqualTo(409);
        var get = call("GET", location, "merchant-a", null, null);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).contains("DEFERRED", "QUERY_PAYMENT_THEN_RESUBMIT");
        assertThat(get.headers().firstValue("Cache-Control")).contains("no-store");
        var changed = call("POST", path, "merchant-a", key, "{\"reason\":\"다른 사유\"}");
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(changed.body()).contains("IDEMPOTENCY_KEY_CONFLICT").doesNotContain("requestStatus");
        var again = call("GET", location, "merchant-a", null, null);
        assertThat(again.body()).isEqualTo(get.body());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_cancellation_requests WHERE merchant_id=997 AND request_key=?",
                        Integer.class,
                        key))
                .isEqualTo(1);
    }

    // 인증 후에도 금액·가맹점 주입, 문자열 강제 변환과 잘못된 키는 저장 전에 거절한다.
    @Test
    void cancellation_whenInputInvalid_rejectsWithoutReservingKey() throws Exception {
        long id = pendingCancellationPayment();
        String path = "/api/payments/" + id + "/cancellations";
        String key = UUID.randomUUID().toString();
        for (String body : List.of(
                "{}",
                "null",
                "[]",
                "{",
                "{\"reason\":123}",
                "{\"reason\":\" 취소\"}",
                "{\"reason\":\"취소\",\"amount\":1}",
                "{\"reason\":\"취소\",\"merchantId\":998}",
                "{\"reason\":\"취소\",\"decisionId\":1}")) {
            var response = call("POST", path, "merchant-a", key, body);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(response.body()).contains("INVALID_CANCELLATION_REQUEST");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
        for (String invalid : List.of("1-1-1-1-1", "bad")) {
            assertThat(call("POST", path, "merchant-a", invalid, "{\"reason\":\"취소\"}")
                            .statusCode())
                    .isEqualTo(400);
            assertThat(call("GET", "/api/payment-cancellation-requests/" + invalid, "merchant-a", null, null)
                            .statusCode())
                    .isEqualTo(400);
        }
        assertThat(call("POST", path, "merchant-a", null, "{\"reason\":\"취소\"}").statusCode())
                .isEqualTo(400);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_cancellation_requests WHERE merchant_id=997 AND request_key=?",
                        Integer.class,
                        key))
                .isZero();
    }

    // 타 가맹점은 결제와 요청 기록을 볼 수 없고 같은 키를 자기 결제에 독립적으로 쓸 수 있다.
    @Test
    void cancellation_whenForeignMerchant_hidesPaymentAndRequest() throws Exception {
        long id = pendingCancellationPayment();
        String key = UUID.randomUUID().toString();
        String path = "/api/payments/" + id + "/cancellations";
        String body = "{\"reason\":\"취소\"}";
        assertThat(call("POST", path, "merchant-a", key, body).statusCode()).isEqualTo(409);
        var foreign = call("POST", path, "merchant-b", key, body);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).contains("PAYMENT_NOT_FOUND");
        var query = call("GET", "/api/payment-cancellation-requests/" + key, "merchant-b", null, null);
        assertThat(query.statusCode()).isEqualTo(404);
        assertThat(query.body()).contains("CANCELLATION_REQUEST_NOT_FOUND");
    }

    // 정지 가맹점도 유효한 키로 기존 결제 요청·조회를 할 수 있고 실행 미연결은 503이다.
    @Test
    void cancellation_whenInactive_allowsExistingPaymentButDoesNotPretendExecution() throws Exception {
        var payment = call(
                "POST",
                "/api/payments",
                "merchant-a",
                UUID.randomUUID().toString(),
                body(UUID.randomUUID().toString(), 1000));
        long id = mapper.readTree(payment.body()).path("paymentId").asLong();
        jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE merchant_id=997");
        try {
            String key = UUID.randomUUID().toString();
            var result =
                    call("POST", "/api/payments/" + id + "/cancellations", "merchant-a", key, "{\"reason\":\"취소\"}");
            assertThat(result.statusCode()).isEqualTo(503);
            assertThat(result.body()).contains("CANCELLATION_NOT_READY").doesNotContain("requestStatus");
            assertThat(call("GET", "/api/payment-cancellation-requests/" + key, "merchant-a", null, null)
                            .statusCode())
                    .isEqualTo(404);
            assertThat(call(
                                    "POST",
                                    "/api/payments",
                                    "merchant-a",
                                    UUID.randomUUID().toString(),
                                    body(UUID.randomUUID().toString(), 1000))
                            .statusCode())
                    .isEqualTo(403);
            long pending = pendingCancellationPayment();
            assertThat(call(
                                    "POST",
                                    "/api/payments/" + pending + "/cancellations",
                                    "merchant-a",
                                    key,
                                    "{\"reason\":\"취소\"}")
                            .statusCode())
                    .isEqualTo(409);
            assertThat(call("GET", "/api/payment-cancellation-requests/" + key, "merchant-a", null, null)
                            .statusCode())
                    .isEqualTo(200);
        } finally {
            jdbc.update("UPDATE merchants SET status='ACTIVE' WHERE merchant_id=997");
        }
    }

    // 새 취소 경로도 본문 파싱 전에 HMAC을 검사하고 HEAD·PUT 등 다른 메서드를 막는다.
    @Test
    void cancellation_whenUnauthenticatedOrWrongMethod_rejectsBeforeController() throws Exception {
        var post = call("POST", "/api/payments/1/cancellations", null, null, "{");
        assertThat(post.statusCode()).isEqualTo(401);
        assertThat(post.headers().firstValue("Cache-Control")).contains("no-store");
        String location = "/api/payment-cancellation-requests/" + UUID.randomUUID();
        assertThat(call("GET", location, null, null, null).statusCode()).isEqualTo(401);
        for (String method : List.of("HEAD", "PUT", "DELETE", "POST")) {
            var result = call(method, location, "merchant-a", null, null);
            assertThat(result.statusCode()).isEqualTo(405);
            assertThat(result.headers().firstValue("Allow")).contains("GET");
        }
        assertThat(call("GET", "/api/payments/1/cancellations", "merchant-a", null, null)
                        .statusCode())
                .isEqualTo(405);
    }
}
