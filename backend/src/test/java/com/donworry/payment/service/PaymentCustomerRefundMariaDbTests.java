package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
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
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import({MariaDbTestContainerConfig.class, PaymentCustomerRefundMariaDbTests.DeliveryConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCustomerRefundMariaDbTests {
    @Autowired
    PaymentCancellationAdmissionService customers;

    @Autowired
    PaymentCancellationQueryService queries;

    @Autowired
    PaymentRepository payments;

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
    PaymentRefunder refunder;

    @MockitoSpyBean
    PaymentRefundCompletion completion;

    @TestConfiguration(proxyBeanMethods = false)
    static class DeliveryConfig {
        @Bean
        PaymentRefundCompletion refundTestCompletion(ApplicationEventPublisher events) {
            return new TestCompletion(events);
        }

        @Bean
        Receiver refundTestReceiver() {
            return new Receiver();
        }
    }

    static class TestCompletion implements PaymentRefundCompletion {
        private final ApplicationEventPublisher events;

        TestCompletion(ApplicationEventPublisher events) {
            this.events = events;
        }

        public void publish(PaymentRefundSucceeded event) {
            events.publishEvent(event);
        }
    }

    public static class Receiver {
        final Map<Long, List<PaymentRefundSucceeded>> received = new ConcurrentHashMap<>();
        final Set<Long> failing = ConcurrentHashMap.newKeySet();
        // P6 대역이다. 정산 차감이나 실제 P6 수신을 검증하는 리스너가 아니다.
        @ApplicationModuleListener
        public void on(PaymentRefundSucceeded event) {
            received.computeIfAbsent(event.paymentId(), id -> new CopyOnWriteArrayList<>())
                    .add(event);
            if (failing.contains(event.paymentId())) throw new IllegalStateException("test receiver failure");
        }
    }

    private Receiver state() {
        return AopTestUtils.getUltimateTargetObject(receiver);
    }

    private Payment payment(String settlementStatus) {
        var p = new TransactionTemplate(manager).execute(s -> {
            var stored =
                    Payment.pending(9983L, "refund-customer", UUID.randomUUID().toString(), 2500L, Instant.now());
            stored.recordAuthorizationSuccess("refund-auth", false);
            stored.completeCapture();
            return payments.save(stored);
        });
        when(captures.findByPaymentId(p.getPaymentId()))
                .thenReturn(Optional.of(new CaptureSnapshot(
                        p.getPaymentId(),
                        p.getPaymentId(),
                        "COMPLETED",
                        p.getAmount(),
                        p.getStatusChangedAt(),
                        true,
                        settlementStatus)));
        return p;
    }

    private PaymentCancellationCommand command(Payment p) {
        return new PaymentCancellationCommand(p.getMerchantId(), p.getPaymentId(), UUID.randomUUID(), "고객 환불");
    }

    private RefundCommand refund(Payment p) {
        return new RefundCommand(p.getPaymentId(), p.getPaymentId(), p.getAmount());
    }

    private long count(String table, Payment p) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE payment_id=?", Long.class, p.getPaymentId());
    }

    private UUID publication(Payment p) {
        return UUID.fromString(jdbc.queryForObject(
                "SELECT ID FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId')=?",
                String.class,
                PaymentRefundSucceeded.class.getName(),
                p.getPaymentId()));
    }

    private void delivered(Payment p) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT COMPLETION_DATE IS NOT NULL FROM EVENT_PUBLICATION WHERE ID=?",
                                Boolean.class,
                                publication(p).toString()))
                        .isTrue());
    }

    // 정산 전후 모두 고객 전액을 한 번 환불하고 매입 사실과 시각은 보존한다.
    @ParameterizedTest
    @ValueSource(strings = {"SCHEDULED", "COMPLETED"})
    void admit_whenSuccessful_preservesCaptureAndReusesRefund(String status) {
        var p = payment(status);
        var c = command(p);
        var first = customers.admit(c);
        assertThat(first.requestStatus()).isEqualTo("SUCCEEDED");
        assertThat(first.operation()).isEqualTo("REFUND");
        assertThat(first.amount()).isEqualTo(2500L);
        assertThat(customers.admit(c)).isEqualTo(first);
        assertThat(queries.find(c.merchantId(), c.requestKey())).isEqualTo(first);
        var linked = customers.admit(command(p));
        assertThat(linked.requestStatus()).isEqualTo("ALREADY_CANCELED");
        assertThat(linked.cancellationId()).isEqualTo(first.cancellationId());
        assertThat(linked.completedAt()).isEqualTo(first.completedAt());
        var stored = payments.findById(p.getPaymentId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(stored.getStatusChangedAt()).isEqualTo(p.getStatusChangedAt());
        assertThat(stored.getAuthorizationRef()).isEqualTo("refund-auth");
        delivered(p);
        assertThat(state().received.get(p.getPaymentId()))
                .containsExactly(new PaymentRefundSucceeded(
                        first.cancellationId(),
                        p.getPaymentId(),
                        p.getPaymentId(),
                        p.getMerchantId(),
                        2500L,
                        first.completedAt()));
        assertThat(count("payment_cancellations", p)).isEqualTo(1);
        verify(refunder).refund(refund(p));
    }

    // 명시적 거절은 실패로 보존하고 다른 요청 키로 환불을 반복하지 않는다.
    @Test
    void admit_whenDeclined_preservesFailureWithoutEvent() {
        var p = payment("COMPLETED");
        var c = command(p);
        doReturn(RefundResult.DECLINED).when(refunder).refund(refund(p));
        var result = customers.admit(c);
        assertThat(result.requestStatus()).isEqualTo("FAILED");
        assertThat(customers.admit(c)).isEqualTo(result);
        assertThat(customers.admit(command(p)).requestStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT error_code FROM payment_cancellations WHERE payment_id=?",
                        String.class,
                        p.getPaymentId()))
                .isEqualTo("REFUND_DECLINED");
        verifyNoInteractions(completion);
        verify(refunder).refund(refund(p));
    }

    // 모의 기술 실패·null·예상 밖 오류·발행 실패는 요청과 작업까지 롤백한다.
    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "null", "unexpected", "publish"})
    void admit_whenFailure_rollsBackAndAllowsSameKeyRetry(String failure) {
        var p = payment("SCHEDULED");
        var c = command(p);
        switch (failure) {
            case "unavailable" ->
                doThrow(new RefundUnavailableException("test")).when(refunder).refund(refund(p));
            case "null" -> doReturn(null).when(refunder).refund(refund(p));
            case "unexpected" ->
                doThrow(new IllegalStateException("test")).when(refunder).refund(refund(p));
            default ->
                doThrow(new IllegalStateException("test publish"))
                        .when(completion)
                        .publish(any());
        }
        assertThatThrownBy(() -> customers.admit(c)).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_cancellation_requests", p)).isZero();
        assertThat(count("payment_cancellations", p)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId')=?",
                        Long.class,
                        PaymentRefundSucceeded.class.getName(),
                        p.getPaymentId()))
                .isZero();
        reset(completion);
        doReturn(RefundResult.SUCCEEDED).when(refunder).refund(refund(p));
        assertThat(customers.admit(c).requestStatus()).isEqualTo("SUCCEEDED");
        delivered(p);
    }

    // 수신 실패를 재전달해도 금융 성공과 완료 시각은 유지하며 환불기를 다시 부르지 않는다.
    @Test
    void admit_whenReceiverFails_redeliversStoredSuccessOnly() {
        var p = payment("COMPLETED");
        var c = command(p);
        state().failing.add(p.getPaymentId());
        var first = customers.admit(c);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(state().received.get(p.getPaymentId())).hasSize(1));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT STATUS FROM EVENT_PUBLICATION WHERE ID=?",
                                String.class,
                                publication(p).toString()))
                        .isEqualTo("FAILED"));
        state().failing.remove(p.getPaymentId());
        publications.resubmitIncompletePublications(
                event -> event.getIdentifier().equals(publication(p)));
        delivered(p);
        assertThat(state().received.get(p.getPaymentId())).hasSize(2).allSatisfy(e -> {
            assertThat(e.cancellationId()).isEqualTo(first.cancellationId());
            assertThat(e.canceledAt()).isEqualTo(first.completedAt());
        });
        assertThat(queries.find(c.merchantId(), c.requestKey())).isEqualTo(first);
        verify(refunder).refund(refund(p));
    }

    // 같은 키와 다른 키의 동시 요청도 하나의 환불 작업으로 합쳐진다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void admit_whenConcurrent_refundsOnce(boolean sameKey) throws Exception {
        var p = payment("COMPLETED");
        var first = command(p);
        var second = sameKey ? first : command(p);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(inv -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    return RefundResult.SUCCEEDED;
                })
                .when(refunder)
                .refund(refund(p));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> customers.admit(first));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var b = pool.submit(() -> customers.admit(second));
            release.countDown();
            assertThat(b.get(15, TimeUnit.SECONDS).cancellationId())
                    .isEqualTo(a.get(15, TimeUnit.SECONDS).cancellationId());
        } finally {
            release.countDown();
        }
        delivered(p);
        assertThat(count("payment_cancellations", p)).isEqualTo(1);
        verify(refunder).refund(refund(p));
    }
}
