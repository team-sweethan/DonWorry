package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.dto.PaymentCancellationResponse;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import com.donworry.settlement.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentCancellationServiceMariaDbTests {
    @Autowired
    PaymentCancellationAdmissionService admission;

    @Autowired
    PaymentCancellationQueryService queries;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentOrderRepository orders;

    @Autowired
    PaymentRequestRepository originals;

    @Autowired
    PaymentActionRepository actions;

    @Autowired
    PaymentCancellationRequestRepository requests;

    @Autowired
    PaymentCancellationRepository cancellations;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    CaptureReader captures;

    private TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }

    private Payment pending(long merchant) {
        return tx().execute(s -> {
            var now = Instant.now();
            var ref = UUID.randomUUID().toString();
            var order = orders.save(PaymentOrder.register(merchant, ref, "customer", 100L, now, now));
            var payment = payments.save(Payment.pending(merchant, "customer", ref, 100L, now));
            originals.save(PaymentRequest.received(
                    order, payment, order.allocateAttempt(now), UUID.randomUUID(), UUID.randomUUID()));
            return payment;
        });
    }

    private PaymentCancellationCommand command(Payment payment) {
        return new PaymentCancellationCommand(
                payment.getMerchantId(), payment.getPaymentId(), UUID.randomUUID(), "고객 취소");
    }

    private void held(Payment payment) {
        tx().executeWithoutResult(s -> {
            var p = payments.findByIdForUpdate(payment.getPaymentId()).orElseThrow();
            var original = originals.findByPaymentId(p.getPaymentId()).orElseThrow();
            original.recordEvaluation(new PaymentEvaluationSnapshot(
                    original.getEvaluationRequestId(), 999L, "REVIEW", 70, null, Instant.now()));
            p.recordAuthorizationSuccess("AUTH", true);
            original.completeAuthorization();
        });
    }
    // 테스트용 저장 fixture이며 실제 금융 실행이나 성공 통지를 대신하지 않는다.
    private PaymentCancellationResponse seedWork(Payment payment, PaymentCancellationStatus status) {
        held(payment);
        var key = UUID.randomUUID();
        tx().executeWithoutResult(s -> {
            var p = payments.findByIdForUpdate(payment.getPaymentId()).orElseThrow();
            var request = requests.save(PaymentCancellationRequest.recordDecision(
                    p, key, "원 요청", PaymentCancellationResolution.ACCEPTED, null, Instant.now()));
            var work = cancellations.save(
                    PaymentCancellation.start(request, p, PaymentCancellationOperation.VOID, null, Instant.now()));
            switch (status) {
                case PROCESSING -> {}
                case UNKNOWN -> work.markUnknown(Instant.now());
                case FAILED -> work.fail("MOCK_INTERNAL_REASON", Instant.now());
                case SUCCEEDED -> {
                    p.completeVoid();
                    work.succeed(Instant.now());
                }
            }
        });
        return queries.find(payment.getMerchantId(), key);
    }

    private void error(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        PaymentHttpException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // 일시 거절은 호출자 롤백과 무관하게 커밋되며 같은 요청의 시각은 보존한다
    @Test
    void admit_whenDeferred_commitsKeyAndPreservesReplay() {
        var c = command(pending(901));
        var first = tx().execute(s -> {
            var result = admission.admit(c);
            s.setRollbackOnly();
            return result;
        });
        assertThat(first.requestStatus()).isEqualTo("DEFERRED");
        assertThat(first.code()).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(first.cancellationId()).isNull();
        assertThat(admission.admit(c)).isEqualTo(first);
        assertThat(queries.find(c.merchantId(), c.requestKey())).isEqualTo(first);
        error(
                () -> admission.admit(
                        new PaymentCancellationCommand(c.merchantId(), c.paymentId(), c.requestKey(), "다른 사유")),
                "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(cancellations.findByPaymentId(c.paymentId())).isEmpty();
    }

    // 인증 가맹점 소유 밖의 결제와 요청은 없는 것과 동일하게 처리하고 키를 만들지 않는다
    @Test
    void admissionAndQuery_whenOtherMerchant_doNotExposeOrReserveKey() {
        var c = command(pending(901));
        error(
                () -> admission.admit(new PaymentCancellationCommand(902L, c.paymentId(), c.requestKey(), c.reason())),
                "PAYMENT_NOT_FOUND");
        assertThat(requests.findByMerchantIdAndRequestKey(902L, c.requestKey())).isEmpty();
        admission.admit(c);
        error(() -> queries.find(902L, c.requestKey()), "CANCELLATION_REQUEST_NOT_FOUND");
        error(() -> queries.find(901L, UUID.randomUUID()), "CANCELLATION_REQUEST_NOT_FOUND");
        var other = pending(902);
        assertThat(admission
                        .admit(new PaymentCancellationCommand(902L, other.getPaymentId(), c.requestKey(), c.reason()))
                        .paymentId())
                .isEqualTo(other.getPaymentId());
    }

    // 조회는 최신 금융 작업이 있어도 이전 일시 거절을 자동 접수로 바꾸지 않는다
    @Test
    void query_whenOtherRequestCompleted_waitsForExplicitResubmission() {
        var payment = pending(901);
        var c = command(payment);
        var first = admission.admit(c);
        var completed = seedWork(payment, PaymentCancellationStatus.SUCCEEDED);
        clearInvocations(captures);
        assertThat(queries.find(901L, c.requestKey())).isEqualTo(first);
        verifyNoInteractions(captures);
        var linked = admission.admit(c);
        assertThat(linked.requestStatus()).isEqualTo("ALREADY_CANCELED");
        assertThat(linked.cancellationId()).isEqualTo(completed.cancellationId());
        assertThat(linked.requestedAt()).isEqualTo(first.requestedAt());
        assertThat(linked.completedAt()).isEqualTo(completed.completedAt());
        assertThat(admission.admit(c)).isEqualTo(linked);
    }

    // 실행부가 없으면 새 작업을 만들지 않고 기존 거절 기록도 그대로 둔다
    @Test
    void admit_whenExecutorMissing_keepsOldRequestAndDoesNotReserveNewKey() {
        var payment = pending(901);
        var c = command(payment);
        var first = admission.admit(c);
        held(payment);
        error(() -> admission.admit(c), "CANCELLATION_NOT_READY");
        assertThat(queries.find(901L, c.requestKey())).isEqualTo(first);
        var newRequest = command(payment);
        error(() -> admission.admit(newRequest), "CANCELLATION_NOT_READY");
        assertThat(requests.findByMerchantIdAndRequestKey(901L, newRequest.requestKey()))
                .isEmpty();
        assertThat(cancellations.findByPaymentId(c.paymentId())).isEmpty();
    }

    // 기존 성공·실패·미확정은 자기 키와 다른 키를 구분하되 새 금융 작업을 만들지 않는다
    @ParameterizedTest
    @EnumSource(PaymentCancellationStatus.class)
    void admit_whenWorkExists_reusesFinancialResult(PaymentCancellationStatus status) {
        var payment = pending(901);
        var original = seedWork(payment, status);
        clearInvocations(captures);
        var replay = admission.admit(
                new PaymentCancellationCommand(901L, payment.getPaymentId(), original.requestKey(), "원 요청"));
        assertThat(replay).isEqualTo(original);
        var other = command(payment);
        var linked = admission.admit(other);
        assertThat(linked.cancellationId()).isEqualTo(original.cancellationId());
        assertThat(linked.completedAt()).isEqualTo(original.completedAt());
        assertThat(linked.requestStatus())
                .isEqualTo(status == PaymentCancellationStatus.SUCCEEDED ? "ALREADY_CANCELED" : status.name());
        String code = switch (status) {
            case SUCCEEDED -> null;
            case FAILED -> "CANCELLATION_PREVIOUSLY_FAILED";
            default -> "CANCELLATION_ALREADY_IN_PROGRESS";
        };
        assertThat(linked.code()).isEqualTo(code);
        if (status == PaymentCancellationStatus.FAILED)
            assertThat(original.code()).isEqualTo("CANCELLATION_DECLINED");
        assertThat(queries.find(901L, other.requestKey())).isEqualTo(linked);
        verifyNoInteractions(captures);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_cancellations WHERE payment_id = ?",
                        Long.class,
                        payment.getPaymentId()))
                .isEqualTo(1);
    }

    // 직원 취소는 원 완료 시각만 반환하며 고객 취소 ID를 만들어내지 않는다
    @Test
    void admit_whenLegacyVoid_preservesExistingActionAndUnknownTime() {
        var payment = pending(901);
        held(payment);
        var completedAt = Instant.parse("2026-10-31T09:00:00Z");
        tx().executeWithoutResult(s -> {
            var p = payments.findByIdForUpdate(payment.getPaymentId()).orElseThrow();
            var action = actions.save(PaymentAction.pending(
                    p.getPaymentId(), p.getPaymentId(), PaymentActionType.VOID, UUID.randomUUID(), completedAt));
            p.completeVoid();
            action.completeVoid(completedAt);
        });
        var response = admission.admit(command(payment));
        assertThat(response.requestStatus()).isEqualTo("ALREADY_CANCELED");
        assertThat(response.cancellationId()).isNull();
        assertThat(response.completedAt()).isEqualTo(completedAt);
        var legacy = pending(901);
        held(legacy);
        tx().executeWithoutResult(s ->
                payments.findByIdForUpdate(legacy.getPaymentId()).orElseThrow().completeVoid());
        var unknown = admission.admit(command(legacy));
        assertThat(unknown.completedAt()).isNull();
        assertThat(unknown.cancellationId()).isNull();
    }

    // 평가 오류는 복구 확인으로 분류하고 명시적 재요청 때만 새 판단을 기록한다
    @Test
    void admit_whenEvaluationError_recordsReviewAndReevaluatesExplicitly() {
        var payment = pending(901);
        tx().executeWithoutResult(s -> {
            var request = originals.findByPaymentId(payment.getPaymentId()).orElseThrow();
            request.recordEvaluation(new PaymentEvaluationSnapshot(
                    request.getEvaluationRequestId(), 999L, "ERROR", null, "TIMEOUT", Instant.now()));
        });
        var c = command(payment);
        var first = admission.admit(c);
        assertThat(first.requestStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(first.code()).isEqualTo("PAYMENT_REVIEW_REQUIRED");
        // 복구 서비스 실행 대역: 현재 평가 단계만 정상 처리 중으로 바꾼다.
        jdbc.update(
                "UPDATE payment_requests SET processing_stage='RECEIVED', evaluation_id=NULL, evaluation_result=NULL, evaluation_error_code=NULL, evaluated_at=NULL WHERE payment_id=?",
                payment.getPaymentId());
        assertThat(queries.find(901L, c.requestKey())).isEqualTo(first);
        assertThat(admission.admit(c).requestStatus()).isEqualTo("DEFERRED");
    }

    // 확정 거절은 같은 키에서 그대로 반환한다
    @Test
    void admit_whenDenied_preservesRejectedResult() {
        var payment = tx().execute(s -> {
            var p = payments.save(
                    Payment.pending(901L, "customer", UUID.randomUUID().toString(), 100L, Instant.now()));
            p.deny();
            return p;
        });
        var c = command(payment);
        var first = admission.admit(c);
        assertThat(first.requestStatus()).isEqualTo("REJECTED");
        clearInvocations(captures);
        assertThat(admission.admit(c)).isEqualTo(first);
        verifyNoInteractions(captures);
    }

    // P6 완료와 P5 상태가 다르면 수정하지 않고 확인 필요로 기록한다
    @Test
    void admit_whenCaptureEvidenceMismatches_requestsReviewWithoutChangingPayment() {
        var payment = pending(901);
        when(captures.findByPaymentId(payment.getPaymentId()))
                .thenReturn(Optional.of(new CaptureSnapshot(
                        payment.getPaymentId(), 99L, "COMPLETED", 100L, Instant.now(), true, "COMPLETED")));
        var result = admission.admit(command(payment));
        assertThat(result.requestStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(payments.findById(payment.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    // 정상 매입은 정산 완료 여부로 거절하지 않되 미연결 실행을 접수하지 않는다
    @Test
    void admit_whenCapturedAndSettled_doesNotCreatePlaceholderWork() {
        var payment = pending(901);
        tx().executeWithoutResult(s -> {
            var p = payments.findByIdForUpdate(payment.getPaymentId()).orElseThrow();
            var request = originals.findByPaymentId(p.getPaymentId()).orElseThrow();
            request.recordEvaluation(new PaymentEvaluationSnapshot(
                    request.getEvaluationRequestId(), 999L, "ALLOW", 10, null, Instant.now()));
            p.recordAuthorizationSuccess("AUTH", false);
            request.completeAuthorization();
            p.completeCapture();
        });
        when(captures.findByPaymentId(payment.getPaymentId()))
                .thenReturn(Optional.of(new CaptureSnapshot(
                        payment.getPaymentId(), 99L, "COMPLETED", 100L, Instant.now(), true, "COMPLETED")));
        var c = command(payment);
        error(() -> admission.admit(c), "CANCELLATION_NOT_READY");
        assertThat(requests.findByMerchantIdAndRequestKey(901L, c.requestKey())).isEmpty();
        assertThat(cancellations.findByPaymentId(c.paymentId())).isEmpty();
    }

    // P6 조회 장애는 금융 거절로 저장하지 않고 기술 오류를 전달한다
    @Test
    void admit_whenCaptureLookupFails_preservesPriorRequest() {
        var payment = pending(901);
        var c = command(payment);
        var first = admission.admit(c);
        var failure = new DataAccessResourceFailureException("capture unavailable");
        when(captures.findByPaymentId(payment.getPaymentId())).thenThrow(failure);
        assertThatThrownBy(() -> admission.admit(c)).isSameAs(failure);
        assertThat(queries.find(901L, c.requestKey())).isEqualTo(first);
        var newKey = command(payment);
        assertThatThrownBy(() -> admission.admit(newKey)).isSameAs(failure);
        assertThat(requests.findByMerchantIdAndRequestKey(901L, newKey.requestKey()))
                .isEmpty();
    }

    // 결과 정합성 실패는 새 연결 요청도 함께 롤백한다
    @Test
    void admit_whenStoredResultContradictsPayment_rollsBackLinkedRequest() {
        var payment = pending(901);
        var source = seedWork(payment, PaymentCancellationStatus.SUCCEEDED);
        jdbc.update("UPDATE payment_cancellations SET amount=200 WHERE cancellation_id=?", source.cancellationId());
        var c = command(payment);
        assertThatIllegalStateException().isThrownBy(() -> admission.admit(c));
        assertThat(requests.findByMerchantIdAndRequestKey(901L, c.requestKey())).isEmpty();
        assertThatIllegalStateException().isThrownBy(() -> queries.find(901L, source.requestKey()));
    }

    // 서로 다른 결제의 동일 키 경합은 하나만 저장하고 패자는 새 트랜잭션에서 내용 충돌로 응답한다
    @Test
    void admit_whenDifferentPaymentsRaceForSameKey_commitsOneRequest() throws Exception {
        var first = pending(901);
        var second = pending(901);
        var key = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        when(captures.findByPaymentId(anyLong())).thenAnswer(invocation -> {
            barrier.await(10, TimeUnit.SECONDS);
            return Optional.empty();
        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.of(first, second).stream()
                    .map(p -> pool.submit(() -> {
                        try {
                            return admission
                                    .admit(new PaymentCancellationCommand(901L, p.getPaymentId(), key, "취소"))
                                    .requestStatus();
                        } catch (PaymentHttpException e) {
                            return e.getCode();
                        }
                    }))
                    .toList();
            assertThat(List.of(
                            tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("DEFERRED", "IDEMPOTENCY_KEY_CONFLICT");
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_cancellation_requests WHERE merchant_id=901 AND request_key=?",
                        Long.class,
                        key.toString()))
                .isEqualTo(1);
    }

    // 같은 결제의 여러 키가 동시에 와도 모두 기존 작업 하나에만 연결한다
    @Test
    void admit_whenDifferentKeysRace_linksOneExistingOperation() throws Exception {
        var payment = pending(901);
        var source = seedWork(payment, PaymentCancellationStatus.UNKNOWN);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<PaymentCancellationResponse>>();
            for (int i = 0; i < 4; i++)
                tasks.add(pool.submit(() -> {
                    start.await();
                    return admission.admit(command(payment));
                }));
            start.countDown();
            for (var task : tasks)
                assertThat(task.get(15, TimeUnit.SECONDS).cancellationId()).isEqualTo(source.cancellationId());
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_cancellations WHERE payment_id=?",
                        Long.class,
                        payment.getPaymentId()))
                .isEqualTo(1);
    }

    // 직원 매입이 결제 잠금을 먼저 잡으면 고객 요청은 커밋 후 조치를 읽어 일시 거절한다
    @Test
    void admit_whenStaffCaptureCommitsFirst_readsActionAfterPaymentLock() throws Exception {
        var payment = pending(901);
        held(payment);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var staff = pool.submit(() -> tx().executeWithoutResult(s -> {
                payments.findByIdForUpdate(payment.getPaymentId()).orElseThrow();
                actions.save(PaymentAction.pending(
                        payment.getPaymentId(),
                        payment.getPaymentId(),
                        PaymentActionType.CAPTURE,
                        UUID.randomUUID(),
                        Instant.now()));
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var customer = pool.submit(() -> admission.admit(command(payment)));
            try {
                assertThatThrownBy(() -> customer.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            staff.get(10, TimeUnit.SECONDS);
            assertThat(customer.get(10, TimeUnit.SECONDS).requestStatus()).isEqualTo("DEFERRED");
        }
    }
}
