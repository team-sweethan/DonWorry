package com.donworry.payment.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.dto.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 이 테스트만 쓰는 HTTP 컨텍스트의 연결 풀을 후속 동시성 검사에 남기지 않는다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MariaDbTestContainerConfig.class)
class PaymentOperationsHttpMariaDbTests {
    private static final String PATH = "/api/payment-operations";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    com.donworry.identity.service.UserSessionService sessions;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentOrderRepository orders;

    @Autowired
    PaymentRequestRepository requests;

    @Autowired
    PaymentActionRepository actions;

    @Autowired
    PaymentFdsRecoveryRepository recoveries;

    @MockitoSpyBean
    PaymentOperationsRepository repository;

    private String prefix;
    private long merchantId;
    private HttpClient staff;

    @BeforeEach
    void setup() throws Exception {
        prefix = "q188" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // 다른 테스트가 논리 참조로만 저장한 가맹점 ID와 자동 증가 ID가 겹치지 않게 한다.
        merchantId = jdbc.queryForObject(
                "SELECT GREATEST(COALESCE((SELECT MAX(merchant_id) FROM merchants), 0), COALESCE((SELECT MAX(merchant_id) FROM payments), 0)) + 1",
                Long.class);
        jdbc.update(
                "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?, ?, '조회 테스트', 'INACTIVE', 0.01, UTC_TIMESTAMP(6))",
                merchantId,
                prefix);
        staff = user("staff", "SECURITY_STAFF", null);
    }

    @AfterEach
    void cleanupAccounts() {
        // 공유 테스트 DB의 시드 계정 수 검사에 임시 계정이 남지 않게 한다.
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

    private HttpClient user(String suffix, String role, Long merchant) throws Exception {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at) VALUES (?,?,'조회 테스트','ACTIVE',?,?,UTC_TIMESTAMP(6))",
                prefix + suffix,
                encoder.encode("OperationsPass123"),
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
                                Map.of("loginId", prefix + suffix, "password", "OperationsPass123"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return caller;
    }

    private HttpResponse<String> get(HttpClient caller, String path) throws Exception {
        return caller.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String path) throws Exception {
        var response = get(staff, path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return mapper.readTree(response.body());
    }

    private long payment(String state, boolean authorized) {
        return new TransactionTemplate(manager).execute(tx -> {
            var p = payments.save(Payment.pending(
                    merchantId, "SECRET_CUSTOMER", UUID.randomUUID().toString(), 1000L, CREATED));
            if (authorized) p.recordAuthorizationSuccess("SECRET_AUTHORIZATION", state.equals("HELD"));
            return p.getPaymentId();
        });
    }

    private long requested(String result) {
        return new TransactionTemplate(manager).execute(tx -> {
            String ref = UUID.randomUUID().toString();
            var p = payments.save(Payment.pending(merchantId, "SECRET_CUSTOMER", ref, 1000L, CREATED));
            var o = orders.save(PaymentOrder.register(merchantId, ref, "SECRET_CUSTOMER", 1000L, CREATED, CREATED));
            o.allocateAttempt(CREATED);
            var r = requests.save(PaymentRequest.received(o, p, 1, UUID.randomUUID(), UUID.randomUUID()));
            if (result != null)
                r.recordEvaluation(new PaymentEvaluationSnapshot(
                        r.getEvaluationRequestId(),
                        1L,
                        result,
                        result.equals("ERROR") ? null : 40,
                        result.equals("ERROR") ? "FDS_ERROR" : null,
                        CREATED));
            return p.getPaymentId();
        });
    }

    private void action(long id, String type, String status) {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var a = actions.save(PaymentAction.pending(
                    id, id, PaymentActionType.valueOf(type), UUID.randomUUID(), CREATED.plusSeconds(60)));
            if (status.equals("FAILED")) a.fail("VOID_DECLINED", CREATED.plusSeconds(90));
            if (status.equals("SUCCEEDED")) {
                if (type.equals("CAPTURE")) a.completeCapture(id, CREATED.plusSeconds(90));
                else a.completeVoid(CREATED.plusSeconds(90));
            }
        });
    }

    // 실제 세션 역할 검사로 비로그인·운영자를 차단하며 직원·관리자는 정지 가맹점도 읽는다.
    @Test
    void access_whenRolesDiffer_enforcesAllRoutesBeforeValidation() throws Exception {
        long id = payment("HELD", true);
        var anonymous = HttpClient.newHttpClient();
        jdbc.update("UPDATE merchants SET status='ACTIVE' WHERE merchant_id=?", merchantId);
        var operator = user("operator", "OPERATOR", merchantId);
        var admin = user("admin", "ADMIN", null);
        jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE merchant_id=?", merchantId);
        for (String path : List.of(PATH, PATH + "/" + id, PATH + "/bad", PATH + "?size=bad")) {
            assertThat(get(anonymous, path).statusCode()).isEqualTo(401);
            assertThat(get(operator, path).statusCode()).isEqualTo(403);
        }
        assertThat(get(admin, PATH + "/" + id).statusCode()).isEqualTo(200);
        assertThat(json(PATH + "/" + id).path("payment").path("merchantId").asLong())
                .isEqualTo(merchantId);
        assertThat(json(PATH + "?merchantId=" + merchantId)
                        .path("totalElements")
                        .asLong())
                .isEqualTo(1);
        var head = anonymous.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH + "/" + id))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(head.statusCode()).isEqualTo(401);
    }

    // 요청 없는 기존 경로도 목록에 남고 상세와 필터가 같은 분류를 사용한다.
    @ParameterizedTest
    @CsvSource({
        "PENDING,false,NONE,NONE,UNKNOWN",
        "PENDING,true,NONE,NONE,AUTO_CAPTURE_PENDING",
        "HELD,true,NONE,NONE,DECISION_PENDING",
        "HELD,true,CAPTURE,PENDING,STAFF_CAPTURE_PENDING",
        "HELD,true,VOID,FAILED,VOID_FAILED",
        "HELD,true,CAPTURE,SUCCEEDED,UNKNOWN",
        "HELD,false,NONE,NONE,UNKNOWN",
        "PENDING,true,VOID,FAILED,UNKNOWN"
    })
    void classification_whenStoredCombinationsMatch_usesSameFilterAndDetail(
            String state, boolean authorized, String type, String actionStatus, String expected) throws Exception {
        long id = payment(state, authorized);
        if (state.equals("HELD") && !authorized)
            jdbc.update("UPDATE payments SET status='HELD' WHERE payment_id=?", id);
        if (!type.equals("NONE")) action(id, type, actionStatus);
        assertThat(json(PATH + "/" + id).path("observation").path("category").asString())
                .isEqualTo(expected);
        var list = json(PATH + "?paymentId=" + id + "&category=" + expected + "&size=1");
        assertThat(list.path("totalElements").asLong()).isEqualTo(1);
        assertThat(list.path("items")
                        .get(0)
                        .path("observation")
                        .path("category")
                        .asString())
                .isEqualTo(expected);
    }

    // 평가 미저장·ERROR·정상 결과와 조치 모순을 구분하며 식별자 원문은 상세에만 둔다.
    @Test
    void evaluation_whenStoredOrAbsent_exposesOnlyAllowedSnapshot() throws Exception {
        long pending = requested(null), error = requested("ERROR"), evaluated = requested("REVIEW");
        var before = json(PATH + "/" + pending);
        assertThat(before.path("evaluation").path("recordStatus").asString()).isEqualTo("NOT_RECORDED");
        assertThat(before.path("evaluation").path("evaluationRequestId").asString())
                .isNotBlank();
        assertThat(before.path("observation").path("category").asString()).isEqualTo("EVALUATION_PENDING");
        assertThat(json(PATH + "/" + error).path("observation").path("category").asString())
                .isEqualTo("EVALUATION_ERROR");
        assertThat(json(PATH + "/" + evaluated)
                        .path("observation")
                        .path("category")
                        .asString())
                .isEqualTo("EVALUATION_PENDING");
        jdbc.update("UPDATE payments SET authorization_ref='SECRET_AUTHORIZATION' WHERE payment_id=?", error);
        assertThat(json(PATH + "/" + error).path("observation").path("category").asString())
                .isEqualTo("UNKNOWN");
        var detail = get(staff, PATH + "/" + error).body();
        assertThat(detail).doesNotContain("SECRET_CUSTOMER", "SECRET_AUTHORIZATION", "customerRef", "authorizationRef");
        assertThat(get(staff, PATH + "?merchantId=" + merchantId).body())
                .doesNotContain(
                        "evaluationRequestId",
                        "requestIdempotencyKey",
                        "actionIdempotencyKey",
                        "SECRET_CUSTOMER",
                        "SECRET_AUTHORIZATION");
    }

    // 복구 기록이 없는 기존 ERROR와 요청 없는 결제는 명시적인 null을 반환한다.
    @Test
    void recovery_whenAbsent_returnsExplicitNull() throws Exception {
        for (long id : List.of(requested("ERROR"), payment("PENDING", false))) {
            var detail = json(PATH + "/" + id);
            assertThat(detail.has("fdsRecovery")).isTrue();
            assertThat(detail.path("fdsRecovery").isNull()).isTrue();
        }
    }

    // 복구 상태와 새 UUID 일치 여부로 결과·사유를 표현하고 반복 GET은 저장값·이벤트를 바꾸지 않는다.
    @ParameterizedTest
    @CsvSource({
        "WAITING_ANALYSIS,false,ERROR,,",
        "READY,false,ERROR,,P2_REASON",
        "REQUESTED,false,ERROR,,P2_REASON",
        "COMPLETED,true,ALLOW,ALLOW,",
        "COMPLETED,true,REVIEW,REVIEW,",
        "COMPLETED,true,DENY,DENY,",
        "MANUAL_REVIEW,false,ERROR,,P2_REASON",
        "MANUAL_REVIEW,true,ERROR,ERROR,NEW_ERROR"
    })
    void recovery_whenRead_mapsOnlyNewEvaluationAndPreservesData(
            String status, boolean matches, String result, String expectedResult, String expectedReason)
            throws Exception {
        long id = requested("ERROR");
        long recoveryId = new TransactionTemplate(manager).execute(tx -> {
            var r = requests.findByPaymentId(id).orElseThrow();
            var recovery = recoveries.save(PaymentFdsRecovery.waitingAnalysis(
                    id,
                    new PaymentEvaluationSnapshot(r.getEvaluationRequestId(), 1L, "ERROR", null, "FDS_ERROR", CREATED),
                    UUID.randomUUID(),
                    CREATED,
                    CREATED));
            if (!status.equals("WAITING_ANALYSIS")) {
                recovery.recordAnalysis(
                        new com.donworry.fds.api.FdsEvaluationFailureAnalyzed(
                                3000000000000L + id,
                                recovery.getFailureReportId(),
                                r.getEvaluationRequestId(),
                                r.getEvaluationRequestId(),
                                id,
                                status.equals("MANUAL_REVIEW") && !matches
                                        ? com.donworry.fds.api.FdsFailureDisposition.MANUAL_REVIEW_REQUIRED
                                        : com.donworry.fds.api.FdsFailureDisposition.REEVALUATION_AVAILABLE,
                                "P2_REASON",
                                CREATED),
                        CREATED);
            }
            return recovery.getRecoveryId();
        });
        // #274 실행 경로는 아직 없으므로 미래 종료 상태만 제약을 지키는 SQL fixture로 구성한다.
        boolean hasWork = status.equals("REQUESTED") || status.equals("COMPLETED") || matches;
        if (hasWork) {
            var newId = UUID.randomUUID();
            jdbc.update(
                    "UPDATE payment_fds_recoveries SET status=?,new_evaluation_request_id=?,retry_id=?,requested_by=1,requested_at=original_evaluated_at,completed_at=CASE WHEN ?='REQUESTED' THEN NULL ELSE original_evaluated_at END WHERE payment_id=?",
                    status,
                    newId.toString(),
                    UUID.randomUUID().toString(),
                    status,
                    id);
            if (matches)
                jdbc.update(
                        "UPDATE payment_requests SET evaluation_request_id=?,evaluation_result=?,evaluation_error_code=?,evaluation_risk_score=?,processing_stage=? WHERE payment_id=?",
                        newId.toString(),
                        result,
                        result.equals("ERROR") ? "NEW_ERROR" : null,
                        result.equals("ERROR") ? null : 40,
                        result.equals("ERROR") ? "NEEDS_REVIEW" : "AUTHORIZED",
                        id);
        }
        var paymentBefore = jdbc.queryForMap("SELECT * FROM payments WHERE payment_id=?", id);
        var requestBefore = jdbc.queryForMap("SELECT * FROM payment_requests WHERE payment_id=?", id);
        var recoveryBefore = jdbc.queryForMap("SELECT * FROM payment_fds_recoveries WHERE payment_id=?", id);
        var eventsBefore = jdbc.queryForList(
                "SELECT * FROM EVENT_PUBLICATION WHERE JSON_VALUE(serialized_event, '$.paymentId') = ?", id);
        for (int i = 0; i < 2; i++) {
            var summary = json(PATH + "/" + id).path("fdsRecovery");
            assertThat(summary.size()).isEqualTo(7);
            assertThat(summary.path("paymentId").asLong()).isEqualTo(id);
            assertThat(summary.path("recoveryId").asLong()).isEqualTo(recoveryId);
            assertThat(summary.path("status").asString()).isEqualTo(status);
            assertThat(summary.path("paymentStatus").asString()).isEqualTo("PENDING");
            assertThat(summary.path("evaluationResult").isNull()).isEqualTo(expectedResult == null);
            if (expectedResult != null)
                assertThat(summary.path("evaluationResult").asString()).isEqualTo(expectedResult);
            assertThat(summary.path("reasonCode").isNull()).isEqualTo(expectedReason == null);
            if (expectedReason != null)
                assertThat(summary.path("reasonCode").asString()).isEqualTo(expectedReason);
            assertThat(summary.path("completedAt").isNull())
                    .isEqualTo(!status.equals("COMPLETED") && !status.equals("MANUAL_REVIEW"));
            assertThat(summary.toString()).doesNotContain("retryId", "evaluationRequestId", "customerRef", "FDS_ERROR");
        }
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE payment_id=?", id))
                .isEqualTo(paymentBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_requests WHERE payment_id=?", id))
                .isEqualTo(requestBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_fds_recoveries WHERE payment_id=?", id))
                .isEqualTo(recoveryBefore);
        assertThat(jdbc.queryForList(
                        "SELECT * FROM EVENT_PUBLICATION WHERE JSON_VALUE(serialized_event, '$.paymentId') = ?", id))
                .isEqualTo(eventsBefore);
    }

    // 필터는 페이지보다 먼저 적용하고 동률은 ID로 정렬하며 완료 단건은 별도로 조회한다.
    @Test
    void list_whenFilteredAndPaged_preservesCountOrderAndCompletedDetail() throws Exception {
        long first = payment("HELD", true), second = payment("HELD", true), done = payment("PENDING", true);
        jdbc.update("UPDATE payments SET status='CAPTURED' WHERE payment_id=?", done);
        var firstPage = json(PATH + "?merchantId=" + merchantId + "&category=DECISION_PENDING&size=1");
        var next = json(PATH + "?merchantId=" + merchantId + "&category=DECISION_PENDING&size=1&page=1");
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(firstPage.path("totalPages").asLong()).isEqualTo(2);
        assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
        assertThat(firstPage.path("items").get(0).path("paymentId").asLong()).isEqualTo(first);
        assertThat(next.path("items").get(0).path("paymentId").asLong()).isEqualTo(second);
        assertThat(next.path("hasNext").asBoolean()).isFalse();
        var empty = json(PATH + "?merchantId=" + merchantId + "&page=99");
        assertThat(empty.path("items").size()).isZero();
        assertThat(empty.path("totalElements").asLong()).isEqualTo(2);
        assertThat(json(PATH + "?paymentId=" + done).path("items").size()).isZero();
        assertThat(json(PATH + "/" + done).path("observation").path("category").asString())
                .isEqualTo("FINANCIAL_COMPLETED");
        assertThat(json(PATH + "?merchantId=" + merchantId
                                + "&createdFrom=2026-01-01T00:00:00Z&createdTo=2026-01-01T00:00:01Z")
                        .path("totalElements")
                        .asLong())
                .isEqualTo(2);
        assertThat(json(PATH + "?merchantId=" + merchantId + "&createdTo=2026-01-01T00:00:00Z")
                        .path("totalElements")
                        .asLong())
                .isZero();
        String order = jdbc.queryForObject("SELECT order_ref FROM payments WHERE payment_id=?", String.class, first);
        assertThat(json(PATH + "?orderRef=" + order + "&paymentStatus=HELD")
                        .path("totalElements")
                        .asLong())
                .isEqualTo(1);
        assertThat(json(PATH + "?orderRef=" + order + "&paymentStatus=PENDING")
                        .path("totalElements")
                        .asLong())
                .isZero();
    }

    // 승인 완료 단계의 정상·모순 조합과 목록 후보 여부를 함께 검사한다.
    @ParameterizedTest
    @CsvSource({
        "ALLOW,PENDING,true,NONE,NONE,AUTO_CAPTURE_PENDING,1",
        "ALLOW,CAPTURED,true,NONE,NONE,FINANCIAL_COMPLETED,0",
        "ALLOW,DECLINED,false,NONE,NONE,PAYMENT_TERMINATED,0",
        "DENY,DENIED,false,NONE,NONE,PAYMENT_TERMINATED,0",
        "REVIEW,HELD,true,NONE,NONE,DECISION_PENDING,1",
        "REVIEW,CAPTURED,true,CAPTURE,SUCCEEDED,FINANCIAL_COMPLETED,0",
        "REVIEW,VOIDED,true,VOID,SUCCEEDED,FINANCIAL_COMPLETED,0",
        "REVIEW,EXPIRED,true,NONE,NONE,PAYMENT_TERMINATED,0",
        "REVIEW,CAPTURED,true,CAPTURE,PENDING,UNKNOWN,1",
        "REVIEW,VOIDED,true,VOID,FAILED,UNKNOWN,1",
        "DENY,HELD,true,NONE,NONE,UNKNOWN,1",
        "ALLOW,HELD,true,NONE,NONE,UNKNOWN,1"
    })
    void authorized_whenFinalOrInconsistent_preservesClassification(
            String result,
            String state,
            boolean authorized,
            String type,
            String actionStatus,
            String expected,
            long candidates)
            throws Exception {
        long id = requested(result);
        jdbc.update("UPDATE payment_requests SET processing_stage='AUTHORIZED' WHERE payment_id=?", id);
        jdbc.update(
                "UPDATE payments SET status=?,authorization_ref=? WHERE payment_id=?",
                state,
                authorized ? "SECRET_AUTHORIZATION" : null,
                id);
        if (!type.equals("NONE")) action(id, type, actionStatus);
        assertThat(json(PATH + "/" + id).path("observation").path("category").asString())
                .isEqualTo(expected);
        assertThat(json(PATH + "?paymentId=" + id + "&category=" + expected)
                        .path("totalElements")
                        .asLong())
                .isEqualTo(candidates);
    }

    // 정보 없음·입력 오류·DB 장애를 HTTP 상태로 구분하고 원본 예외를 숨긴다.
    @Test
    void errors_whenInvalidMissingOrUnavailable_returnDistinctProblems() throws Exception {
        for (String path : List.of(
                PATH + "?size=0",
                PATH + "?size=101",
                PATH + "?size=",
                PATH + "?page=-1",
                PATH + "?size=1&size=2",
                PATH + "?unknown=x",
                PATH + "?createdFrom=bad",
                PATH + "/0",
                PATH + "/bad")) {
            var response = get(staff, path);
            assertThat(response.statusCode()).as(path + response.body()).isEqualTo(400);
            assertThat(response.body()).contains("PAYMENT_OPERATIONS_INVALID_QUERY");
        }
        assertThat(get(staff, PATH + "/" + Long.MAX_VALUE).statusCode()).isEqualTo(404);
        doThrow(new DataAccessResourceFailureException("SECRET_SQL"))
                .when(repository)
                .count(any());
        var unavailable = get(staff, PATH);
        assertThat(unavailable.statusCode()).as(unavailable.body()).isEqualTo(503);
        assertThat(unavailable.body())
                .contains("PAYMENT_OPERATIONS_UNAVAILABLE")
                .doesNotContain("SECRET_SQL");
    }

    // 조회만으로 결제·요청·조치·이벤트가 변경되지 않고 저장된 시각을 그대로 표시한다.
    @Test
    void reads_whenRepeated_doNotMutateBusinessDataOrPublishEvents() throws Exception {
        long id = requested("REVIEW");
        jdbc.update(
                "UPDATE payments SET status='HELD',authorization_ref='SECRET_AUTHORIZATION',status_changed_at=NULL WHERE payment_id=?",
                id);
        jdbc.update("UPDATE payment_requests SET processing_stage='AUTHORIZED' WHERE payment_id=?", id);
        action(id, "CAPTURE", "PENDING");
        var paymentBefore = jdbc.queryForMap("SELECT * FROM payments WHERE payment_id=?", id);
        var requestBefore = jdbc.queryForMap("SELECT * FROM payment_requests WHERE payment_id=?", id);
        var actionBefore = jdbc.queryForMap("SELECT * FROM payment_actions WHERE payment_id=?", id);
        var eventsBefore = jdbc.queryForList(
                "SELECT * FROM EVENT_PUBLICATION WHERE JSON_VALUE(serialized_event, '$.paymentId') = ?", id);
        var detail = json(PATH + "/" + id);
        json(PATH + "?paymentId=" + id);
        assertThat(detail.path("observation").path("waitingSince").asString()).isEqualTo("2026-01-01T00:01:00Z");
        assertThat(detail.path("action").path("completedAt").isNull()).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE payment_id=?", id))
                .isEqualTo(paymentBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_requests WHERE payment_id=?", id))
                .isEqualTo(requestBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_actions WHERE payment_id=?", id))
                .isEqualTo(actionBefore);
        assertThat(jdbc.queryForList(
                        "SELECT * FROM EVENT_PUBLICATION WHERE JSON_VALUE(serialized_event, '$.paymentId') = ?", id))
                .isEqualTo(eventsBefore);
    }
}
