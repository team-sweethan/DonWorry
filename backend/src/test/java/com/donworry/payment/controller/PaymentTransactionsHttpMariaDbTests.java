package com.donworry.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.service.UserSessionService;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentEvaluationSnapshot;
import com.donworry.payment.entity.PaymentOrder;
import com.donworry.payment.entity.PaymentRequest;
import com.donworry.payment.entity.PaymentRiskSignals;
import com.donworry.payment.repository.PaymentOrderRepository;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.payment.repository.PaymentRequestRepository;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 이 테스트만 쓰는 HTTP 컨텍스트의 연결 풀을 후속 동시성 검사에 남기지 않는다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MariaDbTestContainerConfig.class)
class PaymentTransactionsHttpMariaDbTests {
    private static final String PATH = "/api/payment-transactions";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    UserSessionService sessions;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentOrderRepository orders;

    @Autowired
    PaymentRequestRepository requests;

    private String prefix;
    private long merchantId;
    private HttpClient staff;
    private Instant now;

    @BeforeEach
    void setup() throws Exception {
        prefix = "t363" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // 공유 DB의 다른 결제와 섞이지 않게 테스트마다 새 가맹점 ID로 거른다
        merchantId = jdbc.queryForObject(
                "SELECT GREATEST(COALESCE((SELECT MAX(merchant_id) FROM merchants), 0), COALESCE((SELECT MAX(merchant_id) FROM payments), 0)) + 1",
                Long.class);
        jdbc.update(
                "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?, ?, '거래 내역 테스트', 'INACTIVE', 0.01, UTC_TIMESTAMP(6))",
                merchantId,
                prefix);
        staff = user("staff", "SECURITY_STAFF", null);
    }

    @AfterEach
    void cleanupAccounts() {
        // 공유 테스트 DB의 시드 계정 수 검사에 임시 계정이 남지 않게 한다
        var ids =
                jdbc.queryForList("SELECT user_id FROM platform_users WHERE login_id LIKE ?", Long.class, prefix + "%");
        for (Long id : ids) {
            sessions.expireAll(id);
            jdbc.update("DELETE FROM login_histories WHERE user_id=?", id);
            jdbc.update("DELETE FROM user_permissions WHERE user_id=?", id);
            jdbc.update("DELETE FROM platform_users WHERE user_id=?", id);
        }
        jdbc.update("DELETE FROM merchants WHERE merchant_code=?", prefix);
    }

    // 로그인 안 한 사람 · 가맹점 계정은 GET · HEAD 모두 막고, 보안 직원 · 관리자만 읽는다
    @Test
    void access_whenAnonymousOrOperator_rejectsGetAndHead() throws Exception {
        payment(merchantId, prefix + "-cust", now.minus(Duration.ofHours(1)), false);
        var anonymous = HttpClient.newHttpClient();
        jdbc.update("UPDATE merchants SET status='ACTIVE' WHERE merchant_id=?", merchantId);
        var operator = user("operator", "OPERATOR", merchantId);
        var admin = user("admin", "ADMIN", null);
        String path = PATH + "?merchantId=" + merchantId;

        for (String method : List.of("GET", "HEAD")) {
            assertThat(send(anonymous, method, path).statusCode()).isEqualTo(401);
            assertThat(send(operator, method, path).statusCode()).isEqualTo(403);
        }
        assertThat(send(anonymous, "GET", PATH + "?size=bad").statusCode()).isEqualTo(401);
        assertThat(send(admin, "GET", path).statusCode()).isEqualTo(200);
        assertThat(json(path).path("totalElements").asLong()).isEqualTo(1);
    }

    // 카드 토큰 · IP · 기기 ID · 이메일 원문과 승인 참조는 응답 어디에도 없고, 표시값 · 국가 · 가린 값만 나간다
    @Test
    void list_whenSignalsStored_neverExposesRawIdentifiers() throws Exception {
        var signals = PaymentRiskSignals.builder()
                .deviceId("dev-7f3a9c21-raw-device")
                .cardToken("tok_secret_card_363")
                .cardDisplayLabel("신한 ****1234")
                .ipCountryCode("KR")
                .ipAddress("211.234.10.5")
                .buyerEmail("chk990727@gmail.com")
                .build();
        long id = requested(prefix + "-cust", signals, "REVIEW");

        var response = send(staff, "GET", PATH + "?merchantId=" + merchantId);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .doesNotContain(
                        "tok_secret_card_363",
                        "211.234.10.5",
                        "dev-7f3a9c21-raw-device",
                        "chk990727@gmail.com",
                        "SECRET_AUTHORIZATION");
        var item = mapper.readTree(response.body()).path("items").get(0);
        assertThat(item.path("paymentId").asLong()).isEqualTo(id);
        assertThat(item.path("paymentStatus").asString()).isEqualTo("HELD");
        assertThat(item.path("fdsResult").asString()).isEqualTo("REVIEW");
        assertThat(item.path("evaluationId").asLong()).isEqualTo(363L);
        assertThat(item.path("attemptNo").asLong()).isEqualTo(1L);
        assertThat(item.path("cardDisplayLabel").asString()).isEqualTo("신한 ****1234");
        assertThat(item.path("ipCountryCode").asString()).isEqualTo("KR");
        assertThat(item.path("maskedIpAddress").asString()).isEqualTo("211.234.*.*");
        assertThat(item.path("maskedDeviceId").asString()).isEqualTo("dev-7f3a…");
        assertThat(item.path("maskedBuyerEmail").asString()).isEqualTo("ch***@gmail.com");
    }

    // 요청 기록이 없는 결제도 빠지지 않고, 신호 칸은 비어서 나온다
    @Test
    void list_whenPaymentHasNoRequest_stillListed() throws Exception {
        long id = payment(merchantId, prefix + "-cust", now.minus(Duration.ofHours(1)), false);

        var items = json(PATH + "?merchantId=" + merchantId).path("items");
        assertThat(items.size()).isEqualTo(1);
        var item = items.get(0);
        assertThat(item.path("paymentId").asLong()).isEqualTo(id);
        for (String field : List.of("attemptNo", "fdsResult", "evaluationId", "cardDisplayLabel", "maskedIpAddress"))
            assertThat(item.path(field).isNull()).as(field).isTrue();
    }

    // 기본 최근 7일 · 최신순 · 가맹점 · 고객 · 상태 · 주문번호 · 기간 · 페이지가 섞이지 않고 맞게 걸린다
    @Test
    void list_whenFiltered_returnsLatestFirstWithinPeriod() throws Exception {
        String customerA = prefix + "-a";
        long old = payment(merchantId, customerA, now.minus(Duration.ofDays(8)), false);
        long held = payment(merchantId, prefix + "-b", now.minus(Duration.ofHours(2)), true);
        long latest = payment(merchantId, customerA, now.minus(Duration.ofHours(1)), false);
        // 같은 고객 번호를 쓰는 다른 가맹점 결제
        long otherMerchant = payment(merchantId + 1_000_000, customerA, now.minus(Duration.ofMinutes(30)), false);
        String base = PATH + "?merchantId=" + merchantId;

        var defaults = json(base);
        assertThat(ids(defaults)).containsExactly(latest, held);
        assertThat(defaults.path("totalElements").asLong()).isEqualTo(2);

        assertThat(ids(json(base + "&customerRef=" + customerA))).containsExactly(latest);
        assertThat(ids(json(
                        base + "&customerRef=" + customerA + "&from=" + now.minus(Duration.ofDays(10)) + "&to=" + now)))
                .containsExactly(latest, old);
        assertThat(ids(json(base + "&status=HELD"))).containsExactly(held);
        String heldOrder = payments.findById(held).orElseThrow().getOrderRef();
        assertThat(ids(json(base + "&orderRef=" + heldOrder))).containsExactly(held);
        assertThat(ids(json(PATH + "?customerRef=" + customerA))).containsExactly(otherMerchant, latest);

        var secondPage = json(base + "&size=1&page=1");
        assertThat(ids(secondPage)).containsExactly(held);
        assertThat(secondPage.path("totalPages").asLong()).isEqualTo(2);
        assertThat(secondPage.path("hasNext").asBoolean()).isFalse();
    }

    // 대소문자만 다른 주문번호 · 고객 번호는 다른 주문 · 고객이다 — DB 기본 비교(_ci)에 섞이지 않고 정확히 일치만 나온다
    @Test
    void list_whenRefsDifferOnlyByCase_matchesExactly() throws Exception {
        Instant createdAt = now.minus(Duration.ofHours(1));
        long upper = payment(merchantId, "Cust-" + prefix, "ORD-A-" + prefix, createdAt);
        long lower = payment(merchantId, "cust-" + prefix, "ord-a-" + prefix, createdAt);
        String base = PATH + "?merchantId=" + merchantId;

        assertThat(ids(json(base + "&orderRef=ORD-A-" + prefix))).containsExactly(upper);
        assertThat(ids(json(base + "&orderRef=ord-a-" + prefix))).containsExactly(lower);
        assertThat(ids(json(base + "&customerRef=Cust-" + prefix))).containsExactly(upper);
        assertThat(ids(json(base + "&customerRef=cust-" + prefix))).containsExactly(lower);
    }

    private List<Long> ids(JsonNode page) {
        return page.path("items")
                .valueStream()
                .map(i -> i.path("paymentId").asLong())
                .toList();
    }

    private HttpClient user(String suffix, String role, Long merchant) throws Exception {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at) VALUES (?,?,'거래 내역 테스트','ACTIVE',?,?,UTC_TIMESTAMP(6))",
                prefix + suffix,
                encoder.encode("TransactionsPass123"),
                role,
                merchant);
        long id =
                jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, prefix + suffix);
        if (role.equals("OPERATOR")) {
            jdbc.update(
                    "INSERT INTO user_permissions(user_id,permission_code) VALUES (?, 'FDS_VIEW'), (?, 'FINANCE_VIEW')",
                    id,
                    id);
        }
        var caller = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var response = caller.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                                Map.of("loginId", prefix + suffix, "password", "TransactionsPass123"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return caller;
    }

    private HttpResponse<String> send(HttpClient caller, String method, String path) throws Exception {
        return caller.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String path) throws Exception {
        var response = send(staff, "GET", path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return mapper.readTree(response.body());
    }

    private long payment(long merchant, String customerRef, String orderRef, Instant createdAt) {
        return new TransactionTemplate(manager)
                .execute(tx -> payments.save(Payment.pending(merchant, customerRef, orderRef, 1000L, createdAt))
                        .getPaymentId());
    }

    private long payment(long merchant, String customerRef, Instant createdAt, boolean held) {
        return new TransactionTemplate(manager).execute(tx -> {
            var p = payments.save(
                    Payment.pending(merchant, customerRef, UUID.randomUUID().toString(), 1000L, createdAt));
            if (held) p.recordAuthorizationSuccess("SECRET_AUTHORIZATION", true);
            return p.getPaymentId();
        });
    }

    // 검토(REVIEW) 뒤 매입 보류된 결제 — 요청 기록 · 신호 · 승인 참조가 모두 저장된 경우
    private long requested(String customerRef, PaymentRiskSignals signals, String result) {
        return new TransactionTemplate(manager).execute(tx -> {
            String ref = UUID.randomUUID().toString();
            Instant createdAt = now.minus(Duration.ofHours(1));
            var p = payments.save(Payment.pending(merchantId, customerRef, ref, 1000L, createdAt));
            var o = orders.save(PaymentOrder.register(merchantId, ref, customerRef, 1000L, createdAt, createdAt));
            o.allocateAttempt(createdAt);
            var r = requests.save(PaymentRequest.received(o, p, 1, UUID.randomUUID(), UUID.randomUUID(), signals));
            r.recordEvaluation(
                    new PaymentEvaluationSnapshot(r.getEvaluationRequestId(), 363L, result, 40, null, createdAt));
            p.recordAuthorizationSuccess("SECRET_AUTHORIZATION", true);
            return p.getPaymentId();
        });
    }
}
