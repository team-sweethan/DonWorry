package com.donworry.settlement.controller;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("mariadb")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"payment.http.enabled=false", "security.csrf.enabled=true", "settlement.pickup-http.enabled=true"
        })
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PickupAuthenticationMariaDbTests {
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

    @Autowired
    com.donworry.settlement.service.CaptureService captures;

    @MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

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
        jdbc.update("DELETE FROM settlements WHERE merchant_id=?", merchantId);
        jdbc.update("DELETE FROM captures WHERE merchant_id=?", merchantId);
        jdbc.update(
                "DELETE i FROM order_fulfillment_items i JOIN order_fulfillments f ON f.fulfillment_id=i.fulfillment_id WHERE f.order_id IN (?,?)",
                code,
                code + "-OTHER");
        jdbc.update("DELETE FROM order_fulfillments WHERE order_id IN (?, ?)", code, code + "-OTHER");
        jdbc.update("DELETE FROM merchant_request_nonces WHERE merchant_id=?", merchantId);
        jdbc.update("DELETE FROM merchant_integrations WHERE merchant_id=?", merchantId);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchantId);
    }

    private HttpRequest signed(String path) {
        String body = path.endsWith("schedule") ? """
                  {"scheduledPickupAt":"2026-10-27T06:00:00Z",
                   "items":[{"productName":"딸기 케이크","quantity":2}]}
                  """ : "{\"pickedUpAt\":\"2026-10-27T06:00:00Z\"}";
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

    // 실제 HMAC·nonce·P6 어댑터·DB 저장을 거치며 정지 가맹점의 기존 정보 갱신을 허용한다.
    @Test
    void pickup_whenSigned_persistsTenantAndLinksCapture() throws Exception {
        String path = "/api/v1/order-fulfillments/" + code + "/schedule";
        var request = signed(path);
        assertThat(send(request).statusCode()).isEqualTo(200);
        assertThat(send(request).statusCode()).isEqualTo(401);
        assertThat(send(signed(path)).statusCode()).isEqualTo(200);
        assertThat(send(signed("/api/v1/order-fulfillments/" + code + "/completion"))
                        .statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM order_fulfillments WHERE order_id=?", Long.class, code))
                .isEqualTo(merchantId);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_fulfillment_items i JOIN order_fulfillments f ON f.fulfillment_id=i.fulfillment_id WHERE f.order_id=?",
                        Long.class,
                        code))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT i.quantity FROM order_fulfillment_items i JOIN order_fulfillments f ON f.fulfillment_id=i.fulfillment_id WHERE f.order_id=?",
                        Integer.class,
                        code))
                .isEqualTo(2);
        long payment = 9500000L + merchantId;
        captures.processCapture(payment, merchantId, code, 10000L);
        assertThat(jdbc.queryForObject(
                                "SELECT scheduled_at FROM settlements WHERE payment_id=?",
                                java.sql.Timestamp.class,
                                payment)
                        .toLocalDateTime())
                .isEqualTo(java.time.LocalDateTime.parse("2026-10-30T06:00:00"));
    }

    // 인증에 성공해도 다른 가맹점의 주문에는 픽업 정보를 쓰지 않는다.
    @Test
    void pickup_whenDifferentMerchant_createsOwnOrderWithoutForeignMutation() throws Exception {
        String order = code + "-OTHER";
        jdbc.update(
                "INSERT INTO order_fulfillments(merchant_id,order_id,created_at,updated_at) VALUES (?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                merchantId + 1000000,
                order);
        var response = send(signed("/api/v1/order-fulfillments/" + order + "/completion"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT fulfilled_at IS NULL FROM order_fulfillments WHERE order_id=? AND merchant_id=?",
                        Boolean.class,
                        order,
                        merchantId + 1000000))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT fulfilled_at IS NOT NULL FROM order_fulfillments WHERE order_id=? AND merchant_id=?",
                        Boolean.class,
                        order,
                        merchantId))
                .isTrue();
    }
}
