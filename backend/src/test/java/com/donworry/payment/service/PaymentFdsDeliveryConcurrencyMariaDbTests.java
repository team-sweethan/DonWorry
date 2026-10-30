package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.listener.FdsEvaluationFailureAnalyzedListener;
import com.donworry.payment.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("mariadb")
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentFdsDeliveryConcurrencyMariaDbTests {
    @Autowired
    PaymentFdsDeliveryGuard guard;

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

    @MockitoSpyBean
    FdsFailureReporter reporter;

    @Autowired
    PaymentFdsDeliveryService delivery;

    @Autowired
    PaymentFdsDeliverySnapshotService snapshots;

    @MockitoSpyBean
    PaymentFdsStaffAccessService access;

    @Autowired
    IncompleteEventPublications publications;

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

    private UUID failed(FdsEvaluationFailureReport report) {
        doThrow(new IllegalStateException("fixture failure")).when(analysis).receive(any());
        reporter.report(report);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count(report, "FAILED")).isEqualTo(1));
        return UUID.fromString(jdbc.queryForObject(
                "SELECT ID FROM EVENT_PUBLICATION WHERE LISTENER_ID=? AND JSON_VALUE(SERIALIZED_EVENT, '$.failureReportId')=?",
                String.class,
                LISTENER,
                report.failureReportId().toString()));
    }

    private boolean candidate(EventPublication publication, FdsEvaluationFailureReport report) {
        return publication.getStatus() == EventPublication.Status.FAILED
                && publication.getCompletionDate().isEmpty()
                && publication instanceof TargetEventPublication target
                && target.getTargetIdentifier().getValue().equals(LISTENER)
                && publication.getEvent() instanceof FdsEvaluationFailureAnalyzed event
                && event.paymentId().equals(report.paymentId())
                && event.failureReportId().equals(report.failureReportId())
                && event.originalEvaluationRequestId().equals(report.originalEvaluationRequestId())
                && event.evaluationRequestId().equals(report.evaluationRequestId());
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("fixture latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // FAILED 조회 스냅샷을 잡은 요청은 다른 요청이 PROCESSING/COMPLETED로 바꿔도 다시 전달한다.
    // 라이브러리의 현재 경합을 재현하는 특성 테스트이며 안전한 운영 동작을 뜻하지 않는다.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PROCESSING", "COMPLETED"})
    void staleFailedSnapshot_resubmitsAlreadyAdvancedPublication(String advanced) throws Exception {
        var report = prepare("TRANSIENT_TEST");
        UUID id = failed(report);
        var selected = new CountDownLatch(1);
        var releaseStale = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var finished = new CountDownLatch(2);
        doAnswer(invocation -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    if (advanced.equals("PROCESSING")) waitFor(releaseListener);
                    try {
                        return invocation.callRealMethod();
                    } finally {
                        finished.countDown();
                    }
                })
                .when(analysis)
                .receive(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stale = pool.submit(() -> publications.resubmitIncompletePublications(p -> {
                if (!candidate(p, report)) return false;
                assertThat(p.getIdentifier()).isEqualTo(id);
                selected.countDown();
                waitFor(releaseStale);
                return true;
            }));
            try {
                waitFor(selected);
                publications.resubmitIncompletePublications(p -> candidate(p, report));
                waitFor(entered);
                await().atMost(Duration.ofSeconds(10))
                        .untilAsserted(() -> assertThat(count(report, advanced)).isEqualTo(1));
                releaseStale.countDown();
                stale.get(10, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(10))
                        .untilAsserted(() -> assertThat(calls.get()).isEqualTo(2));
            } finally {
                releaseStale.countDown();
                releaseListener.countDown();
            }
        }
        waitFor(finished);
        completed(report, 1);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> verify(analysis, times(3)).receive(any()));
        assertThat(jdbc.queryForObject(
                        "SELECT COMPLETION_ATTEMPTS FROM EVENT_PUBLICATION WHERE ID=?", Integer.class, id.toString()))
                .isEqualTo(3);
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.READY);
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getRetryId())
                .isNull();
        assertThat(payments.findById(report.paymentId()).orElseThrow().getAuthorizationRef())
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=?", Long.class, report.paymentId()))
                .isEqualTo(1);
    }

    // 최신 상태로 다시 선택하는 순차 요청은 완료 기록을 재전달하지 않는다.
    @Test
    void freshSelection_excludesCompletedPublication() {
        var report = prepare("TRANSIENT_TEST");
        UUID id = failed(report);
        doCallRealMethod().when(analysis).receive(any());
        publications.resubmitIncompletePublications(p -> candidate(p, report));
        completed(report, 1);
        publications.resubmitIncompletePublications(p -> candidate(p, report));
        verify(analysis, times(2)).receive(any());
        assertThat(jdbc.queryForObject(
                        "SELECT COMPLETION_ATTEMPTS FROM EVENT_PUBLICATION WHERE ID=?", Integer.class, id.toString()))
                .isEqualTo(2);
    }
    // 전용 잠금은 조회 중인 동시 요청을 거절하고, 재요청은 처리 중/완료 상태를 새로 읽는다.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PROCESSING", "COMPLETED"})
    void guardedSelection_rejectsOverlapAndDoesNotRedeliver(String advanced) throws Exception {
        var report = prepare("TRANSIENT_TEST");
        UUID id = failed(report);
        var selected = new CountDownLatch(1);
        var releaseSelection = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    if (advanced.equals("PROCESSING")) waitFor(releaseListener);
                    try {
                        return invocation.callRealMethod();
                    } finally {
                        finished.countDown();
                    }
                })
                .when(analysis)
                .receive(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> guard.execute(() -> {
                publications.resubmitIncompletePublications(p -> {
                    if (!candidate(p, report)) return false;
                    selected.countDown();
                    waitFor(releaseSelection);
                    return true;
                });
                return null;
            }));
            try {
                waitFor(selected);
                var enteredRejectedCallback = new AtomicInteger();
                assertThatThrownBy(() -> guard.execute(() -> {
                            enteredRejectedCallback.incrementAndGet();
                            publications.resubmitIncompletePublications(p -> candidate(p, report));
                            return null;
                        }))
                        .isInstanceOf(PaymentFdsDeliveryBusyException.class);
                assertThat(enteredRejectedCallback.get()).isZero();
                releaseSelection.countDown();
                first.get(10, TimeUnit.SECONDS);
                waitFor(entered);
                await().atMost(Duration.ofSeconds(10))
                        .untilAsserted(() -> assertThat(count(report, advanced)).isEqualTo(1));
                // 비동기 리스너가 아직 처리 중이어도 잠금을 다시 얻어 최신 상태만 선택한다.
                guard.execute(() -> {
                    publications.resubmitIncompletePublications(p -> candidate(p, report));
                    return null;
                });
                assertThat(jdbc.queryForObject(
                                "SELECT COMPLETION_ATTEMPTS FROM EVENT_PUBLICATION WHERE ID=?",
                                Integer.class,
                                id.toString()))
                        .isEqualTo(2);
            } finally {
                releaseSelection.countDown();
                releaseListener.countDown();
            }
        }
        waitFor(finished);
        completed(report, 1);
        assertThat(calls.get()).isEqualTo(1);
        verify(analysis, times(2)).receive(any());
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.READY);
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getRetryId())
                .isNull();
        assertThat(payments.findById(report.paymentId()).orElseThrow().getAuthorizationRef())
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=?", Long.class, report.paymentId()))
                .isEqualTo(1);
    }

    // 복구 중 예외나 같은 스레드의 중첩 호출에도 잠금이 남지 않아 다른 스레드에서 다시 실행한다.
    @Test
    void guard_releasesAfterFailureAndRejectsReentry() throws Exception {
        assertThatThrownBy(() -> guard.execute(() -> {
                    throw new IllegalStateException("fixture");
                }))
                .isInstanceOf(IllegalStateException.class);
        try (var pool = Executors.newSingleThreadExecutor()) {
            assertThat(pool.submit(() -> guard.execute(() -> {
                                assertThatThrownBy(() -> guard.execute(() -> "nested"))
                                        .isInstanceOf(PaymentFdsDeliveryBusyException.class);
                                return "recovered";
                            }))
                            .get(10, TimeUnit.SECONDS))
                    .isEqualTo("recovered");
        }
        assertThat(guard.execute(() -> "next")).isEqualTo("next");
    }
    // 미전송 보고는 저장된 원본 그대로 전송하고, 진행된 상태에서는 다시 보고하지 않는다.
    @Test
    void delivery_reportsOriginalAndStopsAfterAnalysis() {
        doReturn(1L).when(access).requireStaff();
        var report = prepare("TRANSIENT_TEST");
        assertThat(snapshots.read(report.paymentId()).report()).isEqualTo(report);
        delivery.recover(report.paymentId());
        completed(report, 1);
        assertThat(delivery.recover(report.paymentId()).status()).isEqualTo("READY");
        verify(reporter, times(1)).report(report);
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getRetryId())
                .isNull();
        assertThat(payments.findById(report.paymentId()).orElseThrow().getAuthorizationRef())
                .isNull();
    }

    // 기존 실패 publication만 재전달하며 다른 결제의 실패는 그대로 둔다.
    @Test
    void delivery_replaysOnlyOwnFailure() {
        doReturn(1L).when(access).requireStaff();
        var report = prepare("TRANSIENT_TEST");
        var other = prepare("TRANSIENT_TEST");
        var id = failed(report);
        failed(other);
        doCallRealMethod().when(analysis).receive(any());
        delivery.recover(report.paymentId());
        completed(report, 1);
        assertThat(count(other, "FAILED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COMPLETION_ATTEMPTS FROM EVENT_PUBLICATION WHERE ID=?", Integer.class, id.toString()))
                .isEqualTo(2);
        verify(reporter, times(2)).report(report);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=?", Long.class, report.paymentId()))
                .isEqualTo(1);
    }

    // 진행 중 상태는 중단으로 추정하지 않는다. 상태별 fixture는 실제 재시작 복구를 의미하지 않는다.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PUBLISHED", "PROCESSING", "RESUBMITTED"})
    void delivery_doesNotReplayActiveStates(String status) {
        doReturn(1L).when(access).requireStaff();
        var report = prepare("TRANSIENT_TEST");
        var id = failed(report);
        jdbc.update("UPDATE EVENT_PUBLICATION SET STATUS=? WHERE ID=?", status, id.toString());
        assertThat(delivery.recover(report.paymentId()).status()).isEqualTo("WAITING_ANALYSIS");
        verify(analysis, times(1)).receive(any());
        assertThat(count(report, status)).isEqualTo(1);
    }

    // P2에는 분석이 있지만 전달 기록이 없으면 새 이벤트를 만들어 복구하지 않는다.
    @Test
    void delivery_rejectsMissingPublication() {
        doReturn(1L).when(access).requireStaff();
        var report = prepare("TRANSIENT_TEST");
        var id = failed(report);
        jdbc.update("DELETE FROM EVENT_PUBLICATION WHERE ID=?", id.toString());
        assertThatThrownBy(() -> delivery.recover(report.paymentId()))
                .isInstanceOfSatisfying(
                        PaymentFdsDeliveryNotAllowedException.class,
                        e -> assertThat(e.getReason())
                                .isEqualTo(PaymentFdsDeliveryNotAllowedException.Reason.DELIVERY_RECORD_UNAVAILABLE));
        verify(analysis, times(1)).receive(any());
    }

    // 서비스 진입점도 공통 잠금을 사용하므로 중첩 요청은 P2 호출 전에 거절한다.
    @Test
    void delivery_usesGuardBeforeReporting() {
        doReturn(1L).when(access).requireStaff();
        var report = prepare("TRANSIENT_TEST");
        guard.execute(() -> {
            assertThatThrownBy(() -> delivery.recover(report.paymentId()))
                    .isInstanceOf(PaymentFdsDeliveryBusyException.class);
            return null;
        });
        verifyNoInteractions(reporter);
        delivery.recover(report.paymentId());
        completed(report, 1);
    }
    // 다른 수신자·다른 원본 UUID·완료 기록을 현재 결제의 FAILED 후보로 사용하지 않는다.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"listener", "originalUuid", "completed"})
    void delivery_rejectsUnrelatedOrCompletedPublication(String mismatch) {
        doReturn(1L).when(access).requireStaff();
        var report = prepare("TRANSIENT_TEST");
        var id = failed(report);
        switch (mismatch) {
            case "listener" ->
                jdbc.update("UPDATE EVENT_PUBLICATION SET LISTENER_ID=? WHERE ID=?", "other.listener", id.toString());
            case "originalUuid" ->
                jdbc.update(
                        "UPDATE EVENT_PUBLICATION SET SERIALIZED_EVENT=JSON_SET(SERIALIZED_EVENT, '$.originalEvaluationRequestId', ?) WHERE ID=?",
                        UUID.randomUUID().toString(),
                        id.toString());
            default ->
                jdbc.update(
                        "UPDATE EVENT_PUBLICATION SET STATUS='COMPLETED', COMPLETION_DATE=UTC_TIMESTAMP(6) WHERE ID=?",
                        id.toString());
        }
        assertThatThrownBy(() -> delivery.recover(report.paymentId()))
                .isInstanceOfSatisfying(
                        PaymentFdsDeliveryNotAllowedException.class,
                        e -> assertThat(e.getReason())
                                .isEqualTo(PaymentFdsDeliveryNotAllowedException.Reason.DELIVERY_RECORD_UNAVAILABLE));
        verify(analysis, times(1)).receive(any());
    }

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    // P2 보고 시점에는 다른 트랜잭션이 P5 세 행을 모두 잠글 수 있어야 한다.
    @Test
    void delivery_releasesSnapshotLocksBeforeP2Report() {
        doReturn(1L).when(access).requireStaff();
        var report = prepare("TRANSIENT_TEST");
        doAnswer(invocation -> {
                    try (var pool = Executors.newSingleThreadExecutor()) {
                        pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(
                                                transactionManager)
                                        .execute(status -> {
                                            payments.findByIdForReevaluation(report.paymentId())
                                                    .orElseThrow();
                                            requests.findByPaymentIdForReevaluation(report.paymentId())
                                                    .orElseThrow();
                                            recoveries
                                                    .findByPaymentIdForReevaluation(report.paymentId())
                                                    .orElseThrow();
                                            return true;
                                        }))
                                .get(3, TimeUnit.SECONDS);
                    }
                    return invocation.callRealMethod();
                })
                .when(reporter)
                .report(any());
        delivery.recover(report.paymentId());
        completed(report, 1);
    }

    // P2 실패는 원본과 WAITING_ANALYSIS를 보존하고 잠금을 해제해 다음 요청으로 복구한다.
    @Test
    void delivery_preservesOriginalOnReportingFailure() {
        doReturn(1L).when(access).requireStaff();
        var report = prepare("TRANSIENT_TEST");
        doThrow(new IllegalStateException("fixture failure")).when(reporter).report(any());
        assertThatThrownBy(() -> delivery.recover(report.paymentId())).isInstanceOf(IllegalStateException.class);
        assertThat(snapshots.read(report.paymentId()).report()).isEqualTo(report);
        doCallRealMethod().when(reporter).report(any());
        delivery.recover(report.paymentId());
        completed(report, 1);
    }

    // 원본 저장값이 없으면 P2 보고나 이벤트 조작 전에 거절한다.
    @Test
    void delivery_rejectsMissingRecovery() {
        doReturn(1L).when(access).requireStaff();
        var receipt = admission.admit(new PaymentRequestCommand(
                9000000000273L,
                UUID.randomUUID().toString(),
                "customer",
                100L,
                Instant.now(),
                UUID.randomUUID(),
                PaymentEvaluationFixtures.riskSignals()));
        assertThatThrownBy(() -> delivery.recover(receipt.paymentId()))
                .isInstanceOfSatisfying(
                        PaymentFdsDeliveryNotAllowedException.class,
                        e -> assertThat(e.getReason())
                                .isEqualTo(PaymentFdsDeliveryNotAllowedException.Reason.RECOVERY_DATA_MISSING));
        verifyNoInteractions(reporter);
    }

    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions httpPost(long paymentId) throws Exception {
        return httpPost(paymentId, "fds-recovery-deliveries");
    }

    private org.springframework.test.web.servlet.ResultActions httpPost(long paymentId, String action)
            throws Exception {
        var principal = com.donworry.identity.security.SessionUser.builder()
                .userId(275L)
                .role(com.donworry.identity.entity.UserRole.SECURITY_STAFF)
                .permissions(java.util.List.of())
                .build();
        var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                principal, null, java.util.List.of());
        return mvc.perform(post("/api/payment-operations/" + paymentId + "/" + action)
                .with(authentication(auth))
                .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "http-token"))
                .header("X-XSRF-TOKEN", "http-token"));
    }

    // 실제 HTTP·직원 식별·P2·Modulith·저장을 연결하고 재요청 시 추가 처리가 없는지 검사한다.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void http_recoversMissingReportOrFailedEvent(boolean alreadyReported) throws Exception {
        var report = prepare("TRANSIENT_TEST");
        if (alreadyReported) {
            failed(report);
            doCallRealMethod().when(analysis).receive(any());
        }
        httpPost(report.paymentId())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.paymentId").value(report.paymentId()))
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.isOneOf("WAITING_ANALYSIS", "READY")));
        completed(report, 1);
        httpPost(report.paymentId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        verify(reporter, times(alreadyReported ? 2 : 1)).report(report);
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getRetryId())
                .isNull();
        assertThat(payments.findById(report.paymentId()).orElseThrow().getAuthorizationRef())
                .isNull();
    }

    // 다른 요청이 실제 전용 잠금을 보유하면 HTTP 503이며 P2를 호출하지 않는다.
    @Test
    void http_whenGuardBusy_returns503() throws Exception {
        var report = prepare("TRANSIENT_TEST");
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> guard.execute(() -> {
                acquired.countDown();
                waitFor(release);
                return true;
            }));
            try {
                waitFor(acquired);
                httpPost(report.paymentId())
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("FDS_RECOVERY_DELIVERY_UNAVAILABLE"))
                        .andExpect(header().string("Cache-Control", "no-store"));
                verifyNoInteractions(reporter);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    // 실제 분석은 있으나 전달 기록이 없을 때 HTTP 409로 운영 확인 사유를 돌려준다.
    @Test
    void http_whenPublicationMissing_returns409() throws Exception {
        var report = prepare("TRANSIENT_TEST");
        var id = failed(report);
        jdbc.update("DELETE FROM EVENT_PUBLICATION WHERE ID=?", id.toString());
        httpPost(report.paymentId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FDS_RECOVERY_DELIVERY_NOT_ALLOWED"))
                .andExpect(jsonPath("$.reasonCode").value("DELIVERY_RECORD_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
    // ERROR 전달 실패부터 복구·READY·명시적 재평가까지 연결하고 두 POST 반복으로 새 평가가 늘지 않는다.
    @Test
    void http_recoversThenReevaluatesOnce() throws Exception {
        var report = prepare("TRANSIENT_TEST");
        failed(report);
        doCallRealMethod().when(analysis).receive(any());
        httpPost(report.paymentId()).andExpect(status().isOk());
        completed(report, 1);
        assertThat(recoveries.findByPaymentId(report.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.READY);
        httpPost(report.paymentId(), "fds-reevaluations")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        var row = recoveries.findByPaymentId(report.paymentId()).orElseThrow();
        assertThat(row.getNewEvaluationRequestId()).isNotNull().isNotEqualTo(report.evaluationRequestId());
        assertThat(row.getRetryId()).isNotNull();
        for (String action : java.util.List.of("fds-recovery-deliveries", "fds-reevaluations"))
            httpPost(report.paymentId(), action)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        var replay = recoveries.findByPaymentId(report.paymentId()).orElseThrow();
        assertThat(replay.getNewEvaluationRequestId()).isEqualTo(row.getNewEvaluationRequestId());
        assertThat(replay.getRetryId()).isEqualTo(row.getRetryId());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=?", Long.class, report.paymentId()))
                .isEqualTo(2);
        verify(reporter, times(2)).report(report);
    }
}
