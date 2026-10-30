package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.listener.FdsEvaluationFailureAnalyzedListener;
import com.donworry.payment.repository.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentFdsAnalysisMariaDbTests {
    @Autowired
    PaymentRequestAdmissionService admission;

    @Autowired
    PaymentRequestProcessingService processing;

    @Autowired
    PaymentFdsRecoveryRepository recoveries;

    @Autowired
    PaymentRequestRepository requests;

    @Autowired
    PaymentRepository payments;

    @Autowired
    FdsFailureReporter reporter;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    IncompleteEventPublications publications;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    PaymentFdsAnalysisService analysis;

    private static final String LISTENER = FdsEvaluationFailureAnalyzedListener.class.getName() + ".on("
            + FdsEvaluationFailureAnalyzed.class.getName() + ")";

    private FdsEvaluationFailureReport prepare(String code) {
        var now = Instant.now();
        var receipt = admission.admit(new PaymentRequestCommand(
                9000000000273L,
                UUID.randomUUID().toString(),
                "customer",
                100L,
                now.minusSeconds(1),
                UUID.randomUUID(),
                PaymentEvaluationFixtures.riskSignals()));
        // JDBC Timestamp의 JVM 시간대 변환을 피하고 실제 저장된 UTC 입력을 복사한다.
        jdbc.update(
                "INSERT INTO fds_evaluations (evaluation_request_id, payment_id, merchant_id, customer_ref, payment_requested_at, requested_amount, result, evaluated_at, error_code) SELECT ?, payment_id, merchant_id, customer_ref, created_at, amount, 'ERROR', UTC_TIMESTAMP(6), ? FROM payments WHERE payment_id = ?",
                receipt.evaluationRequestId().toString(),
                code,
                receipt.paymentId());
        long evaluationId = jdbc.queryForObject(
                "SELECT evaluation_id FROM fds_evaluations WHERE evaluation_request_id = ?",
                Long.class,
                receipt.evaluationRequestId().toString());
        return processing
                .saveEvaluation(
                        receipt.paymentId(),
                        new PaymentObservedEvaluation(
                                new FdsEvaluationResponse(
                                        receipt.evaluationRequestId(), evaluationId, FdsResult.ERROR, null, now, code),
                                now,
                                Instant.now()))
                .orElseThrow();
    }

    private FdsEvaluationFailureAnalyzed event(FdsEvaluationFailureReport report, FdsFailureDisposition disposition) {
        return new FdsEvaluationFailureAnalyzed(
                1000000000000L + report.evaluationId(),
                report.failureReportId(),
                report.originalEvaluationRequestId(),
                report.evaluationRequestId(),
                report.paymentId(),
                disposition,
                "TEST_REASON",
                Instant.now());
    }

    private void publish(FdsEvaluationFailureAnalyzed event) {
        new TransactionTemplate(manager).executeWithoutResult(s -> events.publishEvent(event));
    }

    private long count(FdsEvaluationFailureReport report, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE LISTENER_ID = ? AND EVENT_TYPE = ? AND JSON_VALUE(SERIALIZED_EVENT, '$.failureReportId') = ? AND STATUS = ?",
                Long.class,
                LISTENER,
                FdsEvaluationFailureAnalyzed.class.getName(),
                report.failureReportId().toString(),
                status);
    }

    private void completed(FdsEvaluationFailureReport report, long number) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count(report, "COMPLETED")).isEqualTo(number));
    }
    // 실제 P2 보고·커밋 후 P5가 READY만 저장하며 새 평가·승인은 하지 않는다
    @Test
    void report_withRealP2_persistsReadyWithoutReevaluation() {
        var report = prepare("TRANSIENT_TEST");
        reporter.report(report);
        completed(report, 1);
        var row = recoveries.findByPaymentId(report.paymentId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.READY);
        assertThat(row.getReasonCode()).isEqualTo("TRANSIENT_ERROR_RECOVERED");
        assertThat(row.getRetryId()).isNull();
        assertThat(payments.findById(report.paymentId()).orElseThrow().getAuthorizationRef())
                .isNull();
        assertThat(requests.findByPaymentId(report.paymentId()).orElseThrow().getProcessingStage())
                .isEqualTo(PaymentProcessingStage.NEEDS_REVIEW);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id = ?", Long.class, report.paymentId()))
                .isEqualTo(1);
        reporter.report(report);
        assertThat(count(report, "COMPLETED")).isEqualTo(1);
    }
    // 모든 분석 결과의 상태 매핑과 동일 이벤트 재수신 시 종료 시각 보존을 확인한다
    @ParameterizedTest
    @EnumSource(FdsFailureDisposition.class)
    void receive_eachDisposition_isIdempotent(FdsFailureDisposition disposition) {
        var report = prepare("INTERNAL_TEST");
        var event = event(report, disposition);
        publish(event);
        completed(report, 1);
        var first = recoveries.findByPaymentId(report.paymentId()).orElseThrow();
        publish(event);
        completed(report, 2);
        var second = recoveries.findByPaymentId(report.paymentId()).orElseThrow();
        assertThat(second.getStatus())
                .isEqualTo(
                        disposition == FdsFailureDisposition.REEVALUATION_AVAILABLE
                                ? PaymentFdsRecoveryStatus.READY
                                : PaymentFdsRecoveryStatus.MANUAL_REVIEW);
        assertThat(second.getCompletedAt()).isEqualTo(first.getCompletedAt());
        assertThat(second.getReasonCode()).isEqualTo("TEST_REASON");
    }
    // 발행 트랜잭션 롤백은 이벤트 전달과 분석 저장을 남기지 않는다
    @Test
    void publish_whenRolledBack_leavesWaiting() {
        var report = prepare("INTERNAL_TEST");
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            events.publishEvent(event(report, FdsFailureDisposition.REEVALUATION_AVAILABLE));
            s.setRollbackOnly();
        });
        assertThat(count(report, "COMPLETED")).isZero();
        assertThat(count(report, "PUBLISHED")).isZero();
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getAnalysisId())
                .isNull();
    }
    // 수신 실패는 미완료 기록으로 남고 같은 publication을 재전달할 수 있다
    @Test
    void receive_whenFailed_resubmitsSamePublication() {
        var report = prepare("INTERNAL_TEST");
        var event = event(report, FdsFailureDisposition.REEVALUATION_AVAILABLE);
        doThrow(new IllegalStateException("injected failure")).when(analysis).receive(event);
        publish(event);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count(report, "FAILED")).isEqualTo(1));
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getAnalysisId())
                .isNull();
        var id = UUID.fromString(jdbc.queryForObject(
                "SELECT ID FROM EVENT_PUBLICATION WHERE LISTENER_ID = ? AND JSON_VALUE(SERIALIZED_EVENT, '$.failureReportId') = ?",
                String.class,
                LISTENER,
                report.failureReportId().toString()));
        doCallRealMethod().when(analysis).receive(event);
        publications.resubmitIncompletePublications(p -> p.getIdentifier().equals(id));
        completed(report, 1);
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.READY);
    }
    // 식별자 충돌은 분석을 덮어쓰거나 발행 완료로 처리하지 않는다
    @Test
    void receive_whenLineageConflicts_preservesOriginalAndFailsPublication() {
        var report = prepare("INTERNAL_TEST");
        publish(new FdsEvaluationFailureAnalyzed(
                1L,
                report.failureReportId(),
                UUID.randomUUID(),
                report.evaluationRequestId(),
                report.paymentId(),
                FdsFailureDisposition.REEVALUATION_AVAILABLE,
                "TEST",
                Instant.now()));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count(report, "FAILED")).isEqualTo(1));
        var row = recoveries.findByPaymentId(report.paymentId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.WAITING_ANALYSIS);
        assertThat(row.getAnalysisId()).isNull();
        assertThat(count(report, "COMPLETED")).isZero();
    }
    // 늦은 동일 분석은 실행 중·종료 상태 및 최초 실행자를 초기화하지 않는다
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"REQUESTED", "COMPLETED", "MANUAL_REVIEW"})
    void receive_whenLateDuplicate_preservesProgressedState(String status) {
        var report = prepare("TRANSIENT_TEST");
        var event = event(report, FdsFailureDisposition.REEVALUATION_AVAILABLE);
        publish(event);
        completed(report, 1);
        var retry = UUID.randomUUID();
        jdbc.update(
                "UPDATE payment_fds_recoveries SET status = ?, new_evaluation_request_id = UUID(), retry_id = ?, requested_by = 123, requested_at = NOW(6), completed_at = ? WHERE payment_id = ?",
                status,
                retry.toString(),
                status.equals("REQUESTED") ? null : Timestamp.from(Instant.now()),
                report.paymentId());
        publish(event);
        completed(report, 2);
        var row = recoveries.findByPaymentId(report.paymentId()).orElseThrow();
        assertThat(row.getStatus().name()).isEqualTo(status);
        assertThat(row.getRetryId()).isEqualTo(retry);
        assertThat(row.getRequestedBy()).isEqualTo(123L);
        assertThat(row.getReasonCode()).isEqualTo("TEST_REASON");
    }

    // 동시에 전달한 같은 분석은 하나의 결과로 수렴한다
    @Test
    void receive_whenConcurrentDuplicates_keepsOneAnalysis() {
        var report = prepare("INTERNAL_TEST");
        var event = event(report, FdsFailureDisposition.MANUAL_REVIEW_REQUIRED);
        var duplicate = new FdsEvaluationFailureAnalyzed(
                event.analysisId(),
                event.failureReportId(),
                event.originalEvaluationRequestId(),
                event.evaluationRequestId(),
                event.paymentId(),
                event.disposition(),
                event.reasonCode(),
                event.analyzedAt());
        assertThat(duplicate).isEqualTo(event).isNotSameAs(event);
        // Modulith는 객체 identity로 진행 중 publication을 구분한다. 같은 내용을 별도 전달 객체로 발행한다.
        // 두 수신이 실제로 겹친 뒤 DB 잠금·멱등 처리를 수행하도록 시작점을 맞춘다.
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        doAnswer(invocation -> {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return invocation.callRealMethod();
                })
                .when(analysis)
                .receive(event);
        publish(event);
        publish(duplicate);
        completed(report, 2);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(recoveries
                            .findByPaymentId(report.paymentId())
                            .orElseThrow()
                            .getAnalysisId())
                    .isEqualTo(event.analysisId());
            assertThat(count(report, "FAILED")).isZero();
            assertThat(count(report, "COMPLETED")).isEqualTo(2);
        });
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.MANUAL_REVIEW);
    }
}
