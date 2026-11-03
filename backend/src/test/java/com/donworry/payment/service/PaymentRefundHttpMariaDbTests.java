package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.dto.*;
import com.donworry.identity.service.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "payment.http.enabled=true")
@Import({MariaDbTestContainerConfig.class, PaymentCustomerRefundMariaDbTests.DeliveryConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentRefundHttpMariaDbTests {
    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PlatformTransactionManager manager;

    @MockitoBean
    MerchantSecretService secrets;

    @MockitoBean
    CaptureReader captures;

    @MockitoSpyBean
    PaymentRefunder refunder;

    private final byte[] signingKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setup() {
        for (long id : new long[] {9984, 9985}) {
            jdbc.update(
                    "INSERT INTO merchants (merchant_id, merchant_code, display_name, status, pg_fee_rate, secret_reference, created_at) "
                            + "VALUES (?, ?, 'refund HTTP test', 'ACTIVE', 0.01, 'DONWORRY_MERCHANT_HMAC_TEST', UTC_TIMESTAMP(6)) "
                            + "ON DUPLICATE KEY UPDATE status='ACTIVE'",
                    id,
                    "REFUND" + id);
            MerchantIntegrationTestFixtures.installKey(jdbc, id, signingKey);
        }
        when(secrets.resolve(anyString())).thenReturn(new MerchantKeyMaterial(signingKey, 1));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM merchant_request_nonces WHERE merchant_id IN (9984,9985)");
        jdbc.update("DELETE FROM merchant_integrations WHERE merchant_id IN (9984,9985)");
        jdbc.update("DELETE FROM merchants WHERE merchant_code IN ('REFUND9984','REFUND9985')");
    }

    private Payment captured() {
        var p = new TransactionTemplate(manager).execute(s -> {
            var payment =
                    Payment.pending(9984L, "refund-http", UUID.randomUUID().toString(), 3500L, Instant.now());
            payment.recordAuthorizationSuccess("private-refund-auth", false);
            payment.completeCapture();
            return payments.save(payment);
        });
        when(captures.findByPaymentId(p.getPaymentId()))
                .thenReturn(Optional.of(new CaptureSnapshot(
                        p.getPaymentId(),
                        p.getPaymentId(),
                        "COMPLETED",
                        3500L,
                        p.getStatusChangedAt(),
                        true,
                        "COMPLETED")));
        return p;
    }

    private HttpResponse<String> call(String method, String path, long merchant, String key, String body)
            throws Exception {
        String code = "REFUND" + merchant,
                timestamp = Long.toString(Instant.now().getEpochSecond()),
                nonce = UUID.randomUUID().toString();
        var input = new MerchantSignedRequest(
                code,
                timestamp,
                nonce,
                "",
                method,
                path,
                "application/json",
                key == null ? "" : key,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Merchant-Code", code)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", HexFormat.of().formatHex(MerchantHmacSigner.sign(signingKey, input)));
        if (key != null) builder.header("Idempotency-Key", key);
        return client.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String path(Payment p) {
        return "/api/payments/" + p.getPaymentId() + "/cancellations";
    }

    private RefundCommand command(Payment p) {
        return new RefundCommand(p.getPaymentId(), p.getPaymentId(), 3500L);
    }

    private String body() {
        return "{\"reason\":\"고객 환불\"}";
    }

    private long count(String table, Payment p) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE payment_id=?", Long.class, p.getPaymentId());
    }

    // 실제 HMAC·HTTP 성공은 저장된 REFUND 결과로 표현하고 정지 가맹점의 기존 결제도 처리한다.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refund_whenAuthenticated_returnsStableSuccess(boolean inactive) throws Exception {
        var p = captured();
        String key = UUID.randomUUID().toString();
        if (inactive) jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE merchant_id=9984");
        var first = call("POST", path(p), 9984, key, body());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        String location = "/api/payment-cancellation-requests/" + key;
        assertThat(first.headers().firstValue("Location")).contains(location);
        var json = mapper.readTree(first.body());
        assertThat(json.path("operation").asText()).isEqualTo("REFUND");
        assertThat(json.path("requestStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(json.path("amount").asLong()).isEqualTo(3500L);
        assertThat(json.path("completedAt").asText()).isNotBlank();
        assertThat(first.body()).doesNotContain("private-refund-auth", "고객 환불");
        assertThat(mapper.readTree(call("POST", path(p), 9984, key, body()).body()))
                .isEqualTo(json);
        var get = call("GET", location, 9984, null, null);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(get.body())).isEqualTo(json);
        assertThat(get.headers().firstValue("Cache-Control")).contains("no-store");
        var linked =
                mapper.readTree(call("POST", path(p), 9984, UUID.randomUUID().toString(), body())
                        .body());
        assertThat(linked.path("requestStatus").asText()).isEqualTo("ALREADY_CANCELED");
        assertThat(linked.path("cancellationId")).isEqualTo(json.path("cancellationId"));
        assertThat(call("POST", path(p), 9984, key, "{\"reason\":\"다른 사유\"}").statusCode())
                .isEqualTo(409);
        assertThat(payments.findById(p.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CAPTURED);
        verify(refunder).refund(command(p));
    }

    // 명시적 거절은 422, 같은 요청의 재조회는 200/FAILED, 다른 키로는 재실행하지 않는다.
    @Test
    void refund_whenDeclined_returnsStoredFailure() throws Exception {
        var p = captured();
        String key = UUID.randomUUID().toString();
        doReturn(RefundResult.DECLINED).when(refunder).refund(command(p));
        var result = call("POST", path(p), 9984, key, body());
        assertThat(result.statusCode()).isEqualTo(422);
        assertThat(result.body()).contains("CANCELLATION_DECLINED", "FAILED");
        var get = call("GET", "/api/payment-cancellation-requests/" + key, 9984, null, null);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(get.body()).path("operation").asText()).isEqualTo("REFUND");
        assertThat(call("POST", path(p), 9984, UUID.randomUUID().toString(), body())
                        .statusCode())
                .isEqualTo(409);
        verify(refunder).refund(command(p));
    }

    // 기술 오류는 안전한 503이며 미저장 요청 키를 조회한 뒤 같은 키로 재요청할 수 있다.
    @Test
    void refund_whenUnavailable_rollsBackThenAllowsSameKey() throws Exception {
        var p = captured();
        String key = UUID.randomUUID().toString();
        doThrow(new RefundUnavailableException("PRIVATE_TEST_ERROR"))
                .when(refunder)
                .refund(command(p));
        var result = call("POST", path(p), 9984, key, body());
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(result.body())
                .contains("CANCELLATION_UNAVAILABLE")
                .doesNotContain("PRIVATE_TEST_ERROR", "requestStatus");
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(count("payment_cancellation_requests", p)).isZero();
        assertThat(count("payment_cancellations", p)).isZero();
        assertThat(call("GET", "/api/payment-cancellation-requests/" + key, 9984, null, null)
                        .statusCode())
                .isEqualTo(404);
        doReturn(RefundResult.SUCCEEDED).when(refunder).refund(command(p));
        assertThat(call("POST", path(p), 9984, key, body()).statusCode()).isEqualTo(200);
    }

    // 다른 가맹점은 실행과 결과 조회 모두 할 수 없으며 부분 금액·가맹점 지정 본문도 받지 않는다.
    @Test
    void refund_whenForeignOrInvalid_rejectsWithoutExecution() throws Exception {
        var p = captured();
        String key = UUID.randomUUID().toString();
        assertThat(call("POST", path(p), 9985, key, body()).statusCode()).isEqualTo(404);
        for (String bad : List.of("{\"reason\":\"취소\",\"amount\":1}", "{\"reason\":\"취소\",\"merchantId\":9984}")) {
            assertThat(call("POST", path(p), 9984, key, bad).statusCode()).isEqualTo(400);
        }
        verifyNoInteractions(refunder);
        assertThat(count("payment_cancellation_requests", p)).isZero();
        assertThat(call("POST", path(p), 9984, key, body()).statusCode()).isEqualTo(200);
        var other = call("GET", "/api/payment-cancellation-requests/" + key, 9985, null, null);
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(other.body()).contains("CANCELLATION_REQUEST_NOT_FOUND");
    }
}
