package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.*;
import com.donworry.identity.api.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentFdsReevaluationAdmissionMariaDbTests {
    @Autowired
    PaymentFdsReevaluationAdmissionService admission;

    @Autowired
    PaymentRequestAdmissionService initialAdmission;

    @Autowired
    PaymentRequestProcessingService processing;

    @Autowired
    PaymentFdsRecoveryRepository recoveries;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager manager;

    @MockitoBean
    CurrentStaff staff;

    @MockitoBean
    FdsReevaluationRequester reevaluation;

    @MockitoBean
    PaymentAuthorizer authorizer;

    private final ThreadLocal<Long> actor = ThreadLocal.withInitial(() -> 101L);

    @BeforeEach
    void setup() {
        when(staff.findCurrent())
                .thenAnswer(
                        i -> Optional.of(new StaffSummary(actor.get(), "보안 직원", "SECURITY_STAFF", null, List.of())));
    }

    private long prepare(boolean analyzed, FdsFailureDisposition disposition) {
        var now = Instant.now();
        var receipt = initialAdmission.admit(new PaymentRequestCommand(
                9000000000274L,
                UUID.randomUUID().toString(),
                "customer",
                100L,
                now,
                UUID.randomUUID(),
                PaymentEvaluationFixtures.riskSignals()));
        var report = processing
                .saveEvaluation(
                        receipt.paymentId(),
                        new PaymentObservedEvaluation(
                                new FdsEvaluationResponse(
                                        receipt.evaluationRequestId(),
                                        123L,
                                        FdsResult.ERROR,
                                        null,
                                        now,
                                        "TRANSIENT_TEST"),
                                now,
                                now))
                .orElseThrow();
        if (analyzed)
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                var row =
                        recoveries.findByPaymentIdForUpdate(receipt.paymentId()).orElseThrow();
                row.recordAnalysis(
                        new FdsEvaluationFailureAnalyzed(
                                4000000000000L + row.getRecoveryId(),
                                report.failureReportId(),
                                report.originalEvaluationRequestId(),
                                report.evaluationRequestId(),
                                report.paymentId(),
                                disposition,
                                "P2_REASON",
                                now),
                        now);
            });
        return receipt.paymentId();
    }

    private long ready() {
        return prepare(true, FdsFailureDisposition.REEVALUATION_AVAILABLE);
    }

    private PaymentFdsReevaluationAdmission admitAs(long id, long staffId) {
        actor.set(staffId);
        try {
            return admission.admit(id);
        } finally {
            actor.remove();
        }
    }

    private void rejected(long id, PaymentFdsReevaluationNotAllowedException.Reason reason) {
        assertThatThrownBy(() -> admission.admit(id))
                .isInstanceOfSatisfying(
                        PaymentFdsReevaluationNotAllowedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    // 初回接수만 작업 키를 만들고 다른 직원 재요청은 최초 값과 원본을 보존한다.
    @Test
    void admit_whenReady_preservesFirstWorkAndOriginal() {
        long id = ready();
        var paymentBefore = jdbc.queryForMap("SELECT * FROM payments WHERE payment_id=?", id);
        var requestBefore = jdbc.queryForMap("SELECT * FROM payment_requests WHERE payment_id=?", id);
        var original = recoveries.findByPaymentId(id).orElseThrow();
        var first = admitAs(id, 101L);
        var second = admitAs(id, 202L);
        assertThat(second).isEqualTo(first);
        assertThat(first.status()).isEqualTo(PaymentFdsRecoveryStatus.REQUESTED);
        assertThat(first.newEvaluationRequestId()).isNotEqualTo(original.getOriginalEvaluationRequestId());
        assertThat(first.retryId()).isNotNull();
        assertThat(first.requestedBy()).isEqualTo(101L);
        assertThat(first.requestedAt()).isNotNull();
        var after = recoveries.findByPaymentId(id).orElseThrow();
        assertThat(after.getReasonCode()).isEqualTo("P2_REASON");
        assertThat(after.getOriginalEvaluationId()).isEqualTo(original.getOriginalEvaluationId());
        assertThat(after.getFailureReportId()).isEqualTo(original.getFailureReportId());
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE payment_id=?", id))
                .isEqualTo(paymentBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_requests WHERE payment_id=?", id))
                .isEqualTo(requestBefore);
        verifyNoInteractions(reevaluation, authorizer);
    }

    // 분석 대기·불가·복구 자료 부재는 작업을 생성하지 않는다.
    @Test
    void admit_whenUnavailable_rejectsWithReason() {
        long waiting = prepare(false, null);
        long manual = prepare(true, FdsFailureDisposition.MANUAL_REVIEW_REQUIRED);
        rejected(waiting, PaymentFdsReevaluationNotAllowedException.Reason.ANALYSIS_PENDING);
        rejected(manual, PaymentFdsReevaluationNotAllowedException.Reason.ANALYSIS_NOT_ALLOWED);
        long legacy = initialAdmission
                .admit(new PaymentRequestCommand(
                        9000000000274L,
                        UUID.randomUUID().toString(),
                        "customer",
                        100L,
                        Instant.now(),
                        UUID.randomUUID(),
                        PaymentEvaluationFixtures.riskSignals()))
                .paymentId();
        rejected(legacy, PaymentFdsReevaluationNotAllowedException.Reason.RECOVERY_DATA_MISSING);
        assertThatThrownBy(() -> admission.admit(Long.MAX_VALUE)).isInstanceOf(PaymentOperationsException.class);
        assertThat(recoveries.findByPaymentId(waiting).orElseThrow().getRetryId())
                .isNull();
        assertThat(recoveries.findByPaymentId(manual).orElseThrow().getRetryId())
                .isNull();
    }

    // 결제 상태·승인 참조·현재 ERROR의 어느 원본 값이 달라도 신규 접수를 막는다.
    @ParameterizedTest
    @ValueSource(strings = {"status", "authorization", "uuid", "evaluationId", "code", "time", "stage"})
    void admit_whenCurrentStateChanged_rejectsWithoutWork(String field) {
        long id = ready();
        switch (field) {
            case "status" -> jdbc.update("UPDATE payments SET status='EXPIRED' WHERE payment_id=?", id);
            case "authorization" -> jdbc.update("UPDATE payments SET authorization_ref='auth' WHERE payment_id=?", id);
            case "uuid" ->
                jdbc.update("UPDATE payment_requests SET evaluation_request_id=UUID() WHERE payment_id=?", id);
            case "evaluationId" -> jdbc.update("UPDATE payment_requests SET evaluation_id=124 WHERE payment_id=?", id);
            case "code" ->
                jdbc.update("UPDATE payment_requests SET evaluation_error_code='OTHER' WHERE payment_id=?", id);
            case "time" ->
                jdbc.update(
                        "UPDATE payment_requests SET evaluated_at=DATE_ADD(evaluated_at, INTERVAL 1 SECOND) WHERE payment_id=?",
                        id);
            case "stage" ->
                jdbc.update(
                        "UPDATE payment_requests SET processing_stage='EVALUATED',evaluation_result='ALLOW',evaluation_risk_score=0,evaluation_error_code=NULL WHERE payment_id=?",
                        id);
        }
        rejected(id, PaymentFdsReevaluationNotAllowedException.Reason.PAYMENT_STATE_CHANGED);
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getStatus()).isEqualTo(PaymentFdsRecoveryStatus.READY);
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getRetryId()).isNull();
    }

    // 같은 결제의 동시 접수는 한 작업과 한 최초 실행자로 수렴한다.
    @Test
    void admit_whenConcurrent_preservesSingleWork() throws Exception {
        long id = ready();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return admitAs(id, 101L);
            });
            var b = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return admitAs(id, 202L);
            });
            var first = a.get(15, TimeUnit.SECONDS);
            assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(first.requestedBy()).isIn(101L, 202L);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_fds_recoveries WHERE payment_id=?", Long.class, id))
                .isEqualTo(1);
        verifyNoInteractions(reevaluation, authorizer);
    }

    // 별도 접수 커밋은 바깥 실행 실패에도 남아 같은 키로 재개할 수 있다.
    @Test
    void admit_whenOuterTransactionRollsBack_keepsCommittedWork() {
        long id = ready();
        var first = new TransactionTemplate(manager).execute(tx -> {
            var result = admission.admit(id);
            tx.setRollbackOnly();
            return result;
        });
        assertThat(admitAs(id, 202L)).isEqualTo(first);
    }

    // 완료·재평가 ERROR 작업은 결과 반환용으로 재사용하며 미실행 수동 종료는 거절한다.
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "ERROR", "CHANGED"})
    void admit_whenTerminal_neverCreatesAnotherWork(String outcome) {
        long id = ready();
        var first = admission.admit(id);
        jdbc.update(
                "UPDATE payment_fds_recoveries SET status=?,completed_at=UTC_TIMESTAMP(6) WHERE payment_id=?",
                outcome.equals("COMPLETED") ? "COMPLETED" : "MANUAL_REVIEW",
                id);
        if (outcome.equals("ERROR"))
            jdbc.update(
                    "UPDATE payment_requests SET evaluation_request_id=? WHERE payment_id=?",
                    first.newEvaluationRequestId().toString(),
                    id);
        if (outcome.equals("CHANGED"))
            rejected(id, PaymentFdsReevaluationNotAllowedException.Reason.PAYMENT_STATE_CHANGED);
        else {
            var result = admitAs(id, 202L);
            assertThat(result.retryId()).isEqualTo(first.retryId());
            assertThat(result.newEvaluationRequestId()).isEqualTo(first.newEvaluationRequestId());
            assertThat(result.requestedBy()).isEqualTo(101L);
        }
        verifyNoInteractions(reevaluation, authorizer);
    }

    // 재접수도 권한을 다시 검사하고 권한 오류를 잘못된 입력보다 먼저 반환한다.
    @Test
    void admit_whenUnauthorized_rejectsBeforeInputAndExistingWork() {
        long id = ready();
        var first = admission.admit(id);
        for (String role : List.of("ADMIN", "OPERATOR")) {
            when(staff.findCurrent()).thenReturn(Optional.of(new StaffSummary(202L, "테스트 사용자", role, null, List.of())));
            assertThatThrownBy(() -> admission.admit(id)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> admission.admit(null)).isInstanceOf(AccessDeniedException.class);
        }
        when(staff.findCurrent()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> admission.admit(id)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getRetryId()).isEqualTo(first.retryId());
    }

    // 각 잠금 쿼리는 5초 제한으로 실패하고 접수를 롤백하며 세션 설정을 변경하지 않는다.
    @ParameterizedTest
    @ValueSource(strings = {"payments", "payment_requests", "payment_fds_recoveries"})
    void admit_whenRowLocked_timesOutAndCanResume(String table) throws Exception {
        long id = ready();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        int timeout = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForList("SELECT payment_id FROM " + table + " WHERE payment_id=? FOR UPDATE", id);
                locked.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("lock fixture timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();
                assertThatThrownBy(() -> admission.admit(id)).isInstanceOf(PessimisticLockingFailureException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis())
                        .isBetween(4000L, 15000L);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getStatus()).isEqualTo(PaymentFdsRecoveryStatus.READY);
        assertThat(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class))
                .isEqualTo(timeout);
        assertThat(admission.admit(id).status()).isEqualTo(PaymentFdsRecoveryStatus.REQUESTED);
    }
}
