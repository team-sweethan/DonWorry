package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.api.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import com.donworry.settlement.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Tag("mariadb")
@SpringBootTest
@Import({MariaDbTestContainerConfig.class, PaymentCustomerVoidMariaDbTests.DeliveryConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCustomerVoidMariaDbTests {
    @Autowired
    PaymentCancellationAdmissionService customers;

    @Autowired
    PaymentCancellationQueryService queries;

    @Autowired
    PaymentCustomerVoidReader customerVoidReader;

    @Autowired
    PaymentActionRequester staff;

    @Autowired
    PaymentActionAdmissionService staffAdmission;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentCancellationRequestRepository requests;

    @Autowired
    PaymentCancellationRepository works;

    @Autowired
    PaymentActionRepository actions;

    @Autowired
    PaymentCaptureService captureCompletion;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IncompleteEventPublications publications;

    @Autowired
    Receiver receiver;

    @MockitoBean
    CaptureReader captures;

    @MockitoSpyBean
    PaymentVoider voider;

    @MockitoSpyBean
    PaymentCustomerVoidCompletion completion;

    @TestConfiguration(proxyBeanMethods = false)
    static class DeliveryConfig {
        @Bean
        PaymentCustomerVoidCompletion customerVoidTestCompletion(ApplicationEventPublisher events) {
            return new TestCompletion(events);
        }

        @Bean
        Receiver customerVoidTestReceiver(JdbcTemplate jdbc) {
            return new Receiver(jdbc);
        }
    }

    static class TestCompletion implements PaymentCustomerVoidCompletion {
        private final ApplicationEventPublisher events;

        TestCompletion(ApplicationEventPublisher events) {
            this.events = events;
        }

        @Override
        public void publish(PaymentCustomerVoidSucceeded event) {
            events.publishEvent(event);
        }
    }

    public static class Receiver {
        private final JdbcTemplate jdbc;
        final Map<Long, List<PaymentCustomerVoidSucceeded>> received = new ConcurrentHashMap<>();
        final Set<Long> failing = ConcurrentHashMap.newKeySet();

        Receiver(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }
        // P3 대역이며 실제 P3 Case 처리 검증이 아니다. 커밋 후 확정 상태를 읽는다.
        @ApplicationModuleListener
        public void on(PaymentCustomerVoidSucceeded event) {
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM payments WHERE payment_id=?", String.class, event.paymentId()))
                    .isEqualTo("VOIDED");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM payment_cancellations WHERE cancellation_id=?",
                            String.class,
                            event.cancellationId()))
                    .isEqualTo("SUCCEEDED");
            received.computeIfAbsent(event.paymentId(), id -> new CopyOnWriteArrayList<>())
                    .add(event);
            if (failing.contains(event.paymentId())) throw new IllegalStateException("test delivery failure");
        }
    }

    private Receiver state() {
        return AopTestUtils.getUltimateTargetObject(receiver);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }

    private Payment payment(boolean held) {
        return tx().execute(s -> {
            var p = Payment.pending(9981L, "customer-void", UUID.randomUUID().toString(), 2500L, Instant.now());
            if (held) p.recordAuthorizationSuccess("stored-authorization", true);
            return payments.save(p);
        });
    }

    private PaymentCancellationCommand command(Payment p) {
        return new PaymentCancellationCommand(p.getMerchantId(), p.getPaymentId(), UUID.randomUUID(), "고객 취소");
    }

    private PaymentActionCommand staffCommand(Payment p, String type) {
        return new PaymentActionCommand(p.getPaymentId(), p.getPaymentId(), type, UUID.randomUUID());
    }

    private long count(String table, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE payment_id=?", Long.class, id);
    }

    private long eventCount(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId')=?",
                Long.class,
                PaymentCustomerVoidSucceeded.class.getName(),
                id);
    }

    private UUID publication(long id) {
        return UUID.fromString(jdbc.queryForObject(
                "SELECT ID FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId')=?",
                String.class,
                PaymentCustomerVoidSucceeded.class.getName(),
                id));
    }

    private void delivered(long id, int attempts) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(state().received.get(id)).hasSize(attempts);
            assertThat(jdbc.queryForObject(
                            "SELECT COMPLETION_DATE IS NOT NULL FROM EVENT_PUBLICATION WHERE ID=?",
                            Boolean.class,
                            publication(id).toString()))
                    .isTrue();
        });
    }

    // 성공은 결제·고객 작업·통지를 함께 확정하고 재요청은 실행과 시각을 반복하지 않는다.
    @Test
    void admit_whenSuccessful_commitsOnceAndReusesResult() {
        var p = payment(true);
        var c = command(p);
        var first = customers.admit(c);
        assertThat(first.requestStatus()).isEqualTo("SUCCEEDED");
        assertThat(first.operation()).isEqualTo("VOID");
        assertThat(first.amount()).isEqualTo(2500L);
        var stored = payments.findById(p.getPaymentId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(stored.getAuthorizationRef()).isEqualTo("stored-authorization");
        assertThat(stored.getStatusChangedAt()).isEqualTo(first.completedAt());
        assertThat(customers.admit(c)).isEqualTo(first);
        assertThat(queries.find(c.merchantId(), c.requestKey())).isEqualTo(first);
        var linked = customers.admit(command(p));
        assertThat(linked.requestStatus()).isEqualTo("ALREADY_CANCELED");
        assertThat(linked.cancellationId()).isEqualTo(first.cancellationId());
        assertThat(linked.completedAt()).isEqualTo(first.completedAt());
        delivered(p.getPaymentId(), 1);
        assertThat(state().received.get(p.getPaymentId()).getFirst())
                .isEqualTo(new PaymentCustomerVoidSucceeded(
                        first.cancellationId(), p.getPaymentId(), p.getMerchantId(), 2500L, first.completedAt()));
        assertThat(count("payment_actions", p.getPaymentId())).isZero();
        assertThat(count("payment_cancellations", p.getPaymentId())).isEqualTo(1);
        assertThat(eventCount(p.getPaymentId())).isEqualTo(1);
        verify(voider).voidAuthorization(new AuthorizationVoidCommand(p.getPaymentId(), "stored-authorization"));
    }

    // 호출자 스냅샷 이후 커밋된 고객 취소도 새 조회 트랜잭션에서 보며 재조회는 실행하지 않는다.
    @Test
    void findSucceeded_whenOuterSnapshotPredatesCancellation_readsCommittedSuccess() {
        var p = payment(true);
        tx().executeWithoutResult(status -> {
            assertThat(payments.findById(p.getPaymentId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.HELD);
            assertThat(customerVoidReader.findSucceeded(p.getMerchantId(), p.getPaymentId()))
                    .isEmpty();
            var result = customers.admit(command(p));
            var expected = new PaymentCustomerVoidSummary(
                    result.cancellationId(), p.getPaymentId(), p.getMerchantId(), p.getAmount(), result.completedAt());
            assertThat(customerVoidReader.findSucceeded(p.getMerchantId(), p.getPaymentId()))
                    .contains(expected);
            assertThat(customerVoidReader.findSucceeded(p.getMerchantId(), p.getPaymentId()))
                    .contains(expected);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customerVoidReader.findSucceeded(9982L, p.getPaymentId()));
        });
        delivered(p.getPaymentId(), 1);
        assertThat(count("payment_cancellations", p.getPaymentId())).isEqualTo(1);
        assertThat(eventCount(p.getPaymentId())).isEqualTo(1);
        verify(voider).voidAuthorization(new AuthorizationVoidCommand(p.getPaymentId(), "stored-authorization"));
    }

    // 명시적 거절은 실패로 보존하고 새 키·직원 요청으로 금융 실행을 우회하지 않는다.
    @Test
    void admit_whenDeclined_preservesFailureAndBlocksStaff() {
        var p = payment(true);
        var c = command(p);
        doReturn(AuthorizationVoidResult.DECLINED).when(voider).voidAuthorization(any());
        var first = customers.admit(c);
        assertThat(first.requestStatus()).isEqualTo("FAILED");
        assertThat(first.code()).isEqualTo("CANCELLATION_DECLINED");
        assertThat(customers.admit(c)).isEqualTo(first);
        var linked = customers.admit(command(p));
        assertThat(linked.code()).isEqualTo("CANCELLATION_PREVIOUSLY_FAILED");
        assertThat(linked.cancellationId()).isEqualTo(first.cancellationId());
        for (String type : List.of("CAPTURE", "VOID")) {
            assertThat(staff.request(staffCommand(p, type)))
                    .isEqualTo(PaymentActionReceipt.rejected("PAYMENT_CANCELLATION_REVIEW_REQUIRED"));
        }
        assertThat(payments.findById(p.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.HELD);
        assertThat(queries.find(c.merchantId(), c.requestKey())).isEqualTo(first);
        assertThat(eventCount(p.getPaymentId())).isZero();
        assertThat(count("payment_actions", p.getPaymentId())).isZero();
        verify(voider).voidAuthorization(any());
    }

    // 식별한 기술 실패와 응답 없음은 503, 예상 밖 오류는 원래 예외로 남기며 신규 기록을 롤백한다.
    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "null", "unexpected"})
    void admit_whenExecutionFails_rollsBackAndAllowsSameKey(String failure) {
        var p = payment(true);
        var c = command(p);
        if (failure.equals("null")) doReturn(null).when(voider).voidAuthorization(any());
        else if (failure.equals("unavailable"))
            doThrow(new AuthorizationVoidUnavailableException("private detail"))
                    .when(voider)
                    .voidAuthorization(any());
        else doThrow(new IllegalStateException("unexpected")).when(voider).voidAuthorization(any());
        if (failure.equals("unexpected"))
            assertThatThrownBy(() -> customers.admit(c)).isInstanceOf(IllegalStateException.class);
        else
            assertThatThrownBy(() -> customers.admit(c)).isInstanceOfSatisfying(PaymentHttpException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(503);
                assertThat(e.getCode()).isEqualTo("CANCELLATION_UNAVAILABLE");
                assertThat(e.getMessage()).doesNotContain("private detail");
            });
        assertThat(count("payment_cancellation_requests", p.getPaymentId())).isZero();
        assertThat(count("payment_cancellations", p.getPaymentId())).isZero();
        assertThat(eventCount(p.getPaymentId())).isZero();
        assertThat(payments.findById(p.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.HELD);
        doCallRealMethod().when(voider).voidAuthorization(any());
        assertThat(customers.admit(c).requestStatus()).isEqualTo("SUCCEEDED");
    }

    // 재판단 중 실패해도 최초 사유·기록 시각과 기존 확인 필요 상태는 사라지지 않는다.
    @Test
    void admit_whenReconsiderationFails_preservesOriginalRecord() {
        var p = payment(false);
        var c = command(p);
        var original = customers.admit(c);
        assertThat(original.requestStatus()).isEqualTo("REVIEW_REQUIRED");
        tx().executeWithoutResult(s ->
                payments.findByIdForUpdate(p.getPaymentId()).orElseThrow().recordAuthorizationSuccess("AUTH", true));
        doReturn(null).when(voider).voidAuthorization(any());
        assertThatThrownBy(() -> customers.admit(c)).isInstanceOf(PaymentHttpException.class);
        assertThat(queries.find(c.merchantId(), c.requestKey())).isEqualTo(original);
        assertThat(count("payment_cancellations", p.getPaymentId())).isZero();
        doCallRealMethod().when(voider).voidAuthorization(any());
        var success = customers.admit(c);
        assertThat(success.requestStatus()).isEqualTo("SUCCEEDED");
        assertThat(success.requestedAt()).isEqualTo(original.requestedAt());
    }

    // 커밋 직전 또는 성공 발행의 오류는 결제·작업·publication을 함께 롤백한다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void admit_whenCommitOrPublicationFails_rollsBackAll(boolean commitFailure) {
        var p = payment(true);
        var c = command(p);
        if (commitFailure) {
            doAnswer(inv -> {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new IllegalStateException("commit failure");
                            }
                        });
                        return inv.callRealMethod();
                    })
                    .when(voider)
                    .voidAuthorization(any());
        } else
            doThrow(new IllegalStateException("publication failure"))
                    .when(completion)
                    .publish(any());
        assertThatThrownBy(() -> customers.admit(c)).isInstanceOf(IllegalStateException.class);
        assertThat(count("payment_cancellation_requests", p.getPaymentId())).isZero();
        assertThat(count("payment_cancellations", p.getPaymentId())).isZero();
        assertThat(eventCount(p.getPaymentId())).isZero();
        assertThat(payments.findById(p.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.HELD);
        assertThat(state().received).doesNotContainKey(p.getPaymentId());
    }

    // 수신 실패는 금융 성공을 되돌리지 않고 동일 publication만 재전달한다.
    @Test
    void delivery_whenReceiverFails_retriesWithoutFinancialExecution() {
        var p = payment(true);
        state().failing.add(p.getPaymentId());
        var c = command(p);
        var first = customers.admit(c);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT STATUS FROM EVENT_PUBLICATION WHERE ID=?",
                                String.class,
                                publication(p.getPaymentId()).toString()))
                        .isEqualTo("FAILED"));
        assertThat(customers.admit(c)).isEqualTo(first);
        state().failing.remove(p.getPaymentId());
        var id = publication(p.getPaymentId());
        publications.resubmitIncompletePublications(
                event -> event.getIdentifier().equals(id));
        delivered(p.getPaymentId(), 2);
        assertThat(state().received.get(p.getPaymentId()).get(0))
                .isEqualTo(state().received.get(p.getPaymentId()).get(1));
        assertThat(eventCount(p.getPaymentId())).isEqualTo(1);
        verify(voider).voidAuthorization(any());
    }

    // 고객 실행 중 같은 키·다른 키·직원 매입/취소는 결제 잠금 뒤에서 기다리고 추가 실행하지 않는다.
    @ParameterizedTest
    @ValueSource(strings = {"same", "different", "CAPTURE", "VOID"})
    void admission_whenCustomerWins_serializesOtherRequests(String kind) throws Exception {
        var p = payment(true);
        var c = command(p);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(inv -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    return inv.callRealMethod();
                })
                .when(voider)
                .voidAuthorization(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> customers.admit(c));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var second = pool.submit(() -> {
                started.countDown();
                if (kind.equals("CAPTURE") || kind.equals("VOID")) return staff.request(staffCommand(p, kind));
                return customers.admit(kind.equals("same") ? c : command(p));
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            var success = first.get(10, TimeUnit.SECONDS);
            var other = second.get(10, TimeUnit.SECONDS);
            if (kind.equals("same")) assertThat(other).isEqualTo(success);
            else if (kind.equals("different"))
                assertThat(((com.donworry.payment.dto.PaymentCancellationResponse) other).requestStatus())
                        .isEqualTo("ALREADY_CANCELED");
            else assertThat(other).isEqualTo(PaymentActionReceipt.rejected("PAYMENT_CANCELED_BY_CUSTOMER"));
            assertThat(count("payment_cancellations", p.getPaymentId())).isEqualTo(1);
            assertThat(count("payment_actions", p.getPaymentId())).isZero();
            verify(voider).voidAuthorization(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // 직원 매입 접수가 먼저 잠금을 잡으면 고객은 커밋 뒤 일시 거절되고 직원 키 조회는 유지된다.
    @Test
    void admission_whenStaffCaptureWins_defersCustomer() throws Exception {
        var p = payment(true);
        var c = command(p);
        var staffKey = staffCommand(p, "CAPTURE");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx().execute(s -> {
                var admitted = staffAdmission.admit(staffKey);
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return admitted.receipt();
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> customers.admit(c));
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(PaymentActionReceipt.pending());
            assertThat(second.get(10, TimeUnit.SECONDS).requestStatus()).isEqualTo("DEFERRED");
            assertThat(staff.request(staffKey)).isEqualTo(PaymentActionReceipt.pending());
            assertThat(count("payment_cancellations", p.getPaymentId())).isZero();
            verifyNoInteractions(voider);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // 기존 미확정 작업은 정상 HELD여도 직원의 새 금융 조치를 차단한다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void admission_whenOpenCustomerWork_blocksStaff(boolean unknown) {
        var p = payment(true);
        tx().executeWithoutResult(s -> {
            var payment = payments.findByIdForUpdate(p.getPaymentId()).orElseThrow();
            var request = requests.save(PaymentCancellationRequest.recordDecision(
                    payment, UUID.randomUUID(), "기존 작업", PaymentCancellationResolution.ACCEPTED, null, Instant.now()));
            var work = works.save(PaymentCancellation.start(
                    request, payment, PaymentCancellationOperation.VOID, null, Instant.now()));
            if (unknown) work.markUnknown(Instant.now());
        });
        for (String type : List.of("CAPTURE", "VOID")) {
            assertThat(staff.request(staffCommand(p, type)))
                    .isEqualTo(PaymentActionReceipt.rejected("PAYMENT_CANCELLATION_IN_PROGRESS"));
        }
        assertThat(customers.admit(command(p)).requestStatus()).isEqualTo(unknown ? "UNKNOWN" : "PROCESSING");
        assertThat(count("payment_actions", p.getPaymentId())).isZero();
        verifyNoInteractions(voider);
    }

    // 고객 취소 뒤 늦게 도착한 매입 완료는 VOIDED와 성공 결과를 덮지 못한다.
    @Test
    void capture_whenCustomerAlreadyVoided_rejectsLateCompletion() {
        var p = payment(true);
        var c = command(p);
        var success = customers.admit(c);
        assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                        captureCompletion.complete(new CaptureCompleted(p.getPaymentId(), 999L, Instant.now()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(queries.find(c.merchantId(), c.requestKey())).isEqualTo(success);
        assertThat(payments.findById(p.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
    }

    // 직원 취소가 먼저 실행되면 고객은 커밋 뒤 직원 성공 사실만 반환하고 고객 작업을 만들지 않는다.
    @Test
    void admission_whenStaffVoidWins_returnsLegacyWithoutExecutingAgain() throws Exception {
        var p = payment(true);
        var key = staffCommand(p, "VOID");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(inv -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    return inv.callRealMethod();
                })
                .when(voider)
                .voidAuthorization(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> staff.request(key));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> customers.admit(command(p)));
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            var staffResult = first.get(10, TimeUnit.SECONDS);
            assertThat(staffResult.status()).isEqualTo("ACCEPTED");
            var customer = second.get(10, TimeUnit.SECONDS);
            assertThat(customer.requestStatus()).isEqualTo("ALREADY_CANCELED");
            assertThat(customer.cancellationId()).isNull();
            assertThat(customer.completedAt())
                    .isEqualTo(actions.findByPaymentId(p.getPaymentId())
                            .orElseThrow()
                            .getCompletedAt());
            assertThat(staff.request(key)).isEqualTo(staffResult);
            assertThat(count("payment_cancellations", p.getPaymentId())).isZero();
            assertThat(eventCount(p.getPaymentId())).isZero();
            verify(voider).voidAuthorization(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
