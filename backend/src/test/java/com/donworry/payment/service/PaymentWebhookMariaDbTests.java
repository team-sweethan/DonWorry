package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.dto.MerchantKeyMaterial;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.MerchantAuthenticationException;
import com.donworry.identity.service.MerchantSecretService;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 결제 상태 웹훅 발송 (ADR-0064 3절, #402 PR②) — 실제 HTTP 수신 서버(테스트 대역)로 보내 헤더 · 서명 · 재시도를 확인한다
// 로컬 수신(127.0.0.1, http)이라 allow-local-targets를 켠다. 운영 주소 규칙은 PaymentWebhookTargetPolicyTests가 본다
@Tag("mariadb")
@SpringBootTest(properties = {"payment.http.enabled=true", "donworry.payment.webhook.allow-local-targets=true"})
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentWebhookMariaDbTests {

    static final long MERCHANT_ID = 996L;
    static final String MERCHANT_CODE = "HOOK996";
    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Autowired
    PaymentSessionNotifier notifier;

    @Autowired
    PaymentSessionRepository sessions;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JsonMapper json;

    @MockitoBean
    MerchantSecretService secrets;

    HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger responseStatus = new AtomicInteger(200);

    record Received(Map<String, String> headers, byte[] body) {}

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update(
                "INSERT INTO merchants (merchant_id, merchant_code, display_name, status, pg_fee_rate, secret_reference, created_at) "
                        + "VALUES (?, ?, 'Webhook test', 'ACTIVE', 0.01, 'DONWORRY_MERCHANT_HMAC_TEST', UTC_TIMESTAMP(6)) "
                        + "ON DUPLICATE KEY UPDATE status='ACTIVE'",
                MERCHANT_ID,
                MERCHANT_CODE);
        when(secrets.resolve(anyString())).thenReturn(new MerchantKeyMaterial(KEY, 1));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hooks/donworry", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(
                    Map.of(
                            "merchant", header(exchange, "X-Merchant-Code"),
                            "timestamp", header(exchange, "X-Timestamp"),
                            "event", header(exchange, "X-Event-Id"),
                            "signature", header(exchange, "X-Signature"),
                            "contentType", header(exchange, "Content-Type")),
                    body));
            int status = responseStatus.get();
            if (status == 302) exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // 결제창 결제가 보류되면 서명된 웹훅을 한 번 보내고, 상태가 그대로면 다시 보내지 않는다.
    // 직원 거절로 VOIDED가 되면 새 eventId로 한 번 더 보낸다
    @Test
    void run_sendsSignedWebhookOncePerStatusChange() throws Exception {
        Ids ids = heldSessionWithNotifyUrl();

        notifier.run();

        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.headers()).containsEntry("merchant", MERCHANT_CODE);
            assertThat(r.headers()).containsEntry("event", "evt_" + ids.paymentId() + "_HELD_A");
            assertThat(r.headers().get("contentType")).startsWith("application/json");
            assertThat(r.headers().get("signature"))
                    .isEqualTo(expectedSignature(
                            r.headers().get("timestamp"), r.headers().get("event"), r.body()));
            JsonNode body = json.readTree(r.body());
            assertThat(body.get("type").asString()).isEqualTo("payment.status_changed");
            assertThat(body.get("paymentId").asLong()).isEqualTo(ids.paymentId());
            assertThat(body.get("paymentStatus").asString()).isEqualTo("HELD");
            assertThat(body.get("authorized").asBoolean()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(4000L);
        });
        PaymentSession notified = sessions.findById(ids.sessionId()).orElseThrow();
        assertThat(notified.getNotifiedStatus()).isEqualTo("HELD");
        assertThat(notified.getNextNotifyAt()).isNull();

        notifier.run();
        assertThat(received).hasSize(1);

        tx().executeWithoutResult(s ->
                payments.findByIdForUpdate(ids.paymentId()).orElseThrow().completeVoid());
        notifier.run();

        assertThat(received).hasSize(2);
        assertThat(received.get(1).headers()).containsEntry("event", "evt_" + ids.paymentId() + "_VOIDED_A");
        assertThat(sessions.findById(ids.sessionId()).orElseThrow().getNotifiedStatus())
                .isEqualTo("VOIDED");
    }

    // 2xx가 아니면(5xx · 리다이렉트) 보낸 것으로 기록하지 않고 5초부터 늘어나는 간격 뒤에 다시 보낸다
    @Test
    void run_whenNot2xx_retriesLaterWithoutMarkingNotified() {
        for (int status : new int[] {500, 302}) {
            received.clear();
            responseStatus.set(status);
            Ids ids = heldSessionWithNotifyUrl();

            notifier.run();

            assertThat(received).as("status " + status).hasSize(1);
            PaymentSession failed = sessions.findById(ids.sessionId()).orElseThrow();
            assertThat(failed.getNotifiedStatus()).as("status " + status).isNull();
            assertThat(failed.getNotifyAttempts()).isEqualTo(1);
            assertThat(failed.getNextNotifyAt()).isAfter(Instant.now().plusSeconds(5));

            // 다음 시각 전에는 다시 보내지 않는다
            notifier.run();
            assertThat(received).as("status " + status).hasSize(1);
        }
    }

    // 가맹점 키가 없으면 서명 없이 보내지 않고 다시 시도로 남긴다
    @Test
    void run_whenNoUsableKey_doesNotSendUnsigned() {
        when(secrets.resolve(anyString()))
                .thenThrow(new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED));
        Ids ids = heldSessionWithNotifyUrl();

        notifier.run();

        assertThat(received).isEmpty();
        PaymentSession pending = sessions.findById(ids.sessionId()).orElseThrow();
        assertThat(pending.getNotifiedStatus()).isNull();
        assertThat(pending.getNotifyAttempts()).isEqualTo(1);
    }

    record Ids(Long sessionId, Long paymentId) {}

    // 결제창으로 접수돼 보류(HELD)된 결제와, 알림 주소가 있는 세션
    private Ids heldSessionWithNotifyUrl() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String orderRef = "HOOK-" + UUID.randomUUID();
        return tx().execute(status -> {
            Payment payment = Payment.pending(MERCHANT_ID, "customer-hook", orderRef, 4000L, now);
            payment.recordAuthorizationSuccess("stored-authorization", true);
            payments.save(payment);
            PaymentSession session = PaymentSession.builder()
                    .sessionKey("pss_" + UUID.randomUUID().toString().replace("-", ""))
                    .merchantId(MERCHANT_ID)
                    .requestKey(UUID.randomUUID())
                    .attemptKey(UUID.randomUUID())
                    .orderRef(orderRef)
                    .customerRef("42")
                    .amount(4000L)
                    .orderCreatedAt(now.minusSeconds(10))
                    .orderName("웹훅 확인 주문")
                    .successUrl("http://127.0.0.1:8080/orders/1/payment/donworry/return")
                    .failUrl("http://127.0.0.1:8080/orders/1/payment/fail")
                    .notifyUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hooks/donworry")
                    .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                    .createdAt(now)
                    .build();
            sessions.save(session);
            session.submitted(payment.getPaymentId(), now);
            return new Ids(session.getSessionId(), payment.getPaymentId());
        });
    }

    // ADR-0064 3절 서명 원문을 테스트에서 따로 계산한다
    private static String expectedSignature(String timestamp, String eventId, byte[] body) throws Exception {
        String bodyHash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String canonical = String.join("\n", "DONWORRY-WEBHOOK-V1", MERCHANT_CODE, timestamp, eventId, bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String header(com.sun.net.httpserver.HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }
}
