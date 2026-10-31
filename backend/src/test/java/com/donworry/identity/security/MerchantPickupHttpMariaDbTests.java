package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.dto.MerchantSignedRequest;
import com.donworry.identity.repository.MerchantRequestNonceRepository;
import com.donworry.identity.service.MerchantHmacSigner;
import com.donworry.identity.service.MerchantSecretService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("mariadb")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"payment.http.enabled=false", "security.csrf.enabled=true"})
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MerchantPickupHttpMariaDbTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    MerchantSecretService secrets;

    @MockitoSpyBean
    MerchantRequestNonceRepository nonces;

    private final HttpClient client = HttpClient.newHttpClient();
    private final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private String code;
    private Long merchantId;

    @BeforeEach
    void setUp() {
        code = "PICKUP" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        jdbc.update(
                "INSERT INTO merchants (merchant_code,display_name,status,pg_fee_rate,secret_reference,created_at) "
                        + "VALUES (?, 'pickup test', 'INACTIVE', 0.01, 'DONWORRY_MERCHANT_HMAC_TEST', UTC_TIMESTAMP(6))",
                code);
        merchantId = jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, code);
        com.donworry.identity.service.MerchantIntegrationTestFixtures.installKey(jdbc, merchantId, key);
        when(secrets.resolve(anyString())).thenReturn(new com.donworry.identity.dto.MerchantKeyMaterial(key, 1));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM merchant_request_nonces WHERE merchant_id=?", merchantId);
        jdbc.update("DELETE FROM merchant_integrations WHERE merchant_id=?", merchantId);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchantId);
    }

    private HttpRequest signed(String path) {
        String body = "{\"" + (path.endsWith("schedule") ? "scheduledPickupAt" : "pickedUpAt")
                + "\":\"2026-10-27T06:00:00Z\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        var input = new MerchantSignedRequest(
                code, timestamp, nonce, "", "PUT", path, "application/json", "", body.getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Merchant-Code", code)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", HexFormat.of().formatHex(MerchantHmacSigner.sign(key, input)))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // P6 Controller 부재와 P5 비활성에도 실제 PUT 인증·정지 허용·DB nonce 소비를 유지한다
    @ParameterizedTest
    @ValueSource(strings = {"schedule", "completion"})
    void pickup_whenControllerAbsent_authenticatesAndPersistsNonce(String action) throws Exception {
        String path = "/api/v1/order-fulfillments/ORDER-1/" + action;
        var unsigned = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertThat(send(unsigned).statusCode()).isEqualTo(401);
        var request = signed(path);
        var first = send(request);
        assertThat(first.statusCode()).isEqualTo(404);
        assertThat(first.headers().allValues("Set-Cookie")).isEmpty();
        var replay = send(request);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(replay.body()).contains("MERCHANT_AUTH_001");
        assertThat(send(signed(path)).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_request_nonces WHERE merchant_id=?", Integer.class, merchantId))
                .isEqualTo(2);
        var wrongMethod = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        var rejected = send(wrongMethod);
        assertThat(rejected.statusCode()).isEqualTo(405);
        assertThat(rejected.headers().firstValue("Allow")).contains("PUT");
    }

    // 실제 nonce INSERT 실패는 인증 성공이나 단순 Controller 부재로 처리하지 않고 503으로 차단한다
    @ParameterizedTest
    @ValueSource(strings = {"schedule", "completion"})
    void pickup_whenNonceInsertFails_returnsUnavailableWithoutNonce(String action) throws Exception {
        doThrow(new DataAccessResourceFailureException("test unavailable"))
                .when(nonces)
                .insertNonce(anyLong(), anyString(), anyString());
        var response = send(signed("/api/v1/order-fulfillments/ORDER-1/" + action));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("MERCHANT_AUTH_002");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_request_nonces WHERE merchant_id=?", Integer.class, merchantId))
                .isZero();
    }
}
