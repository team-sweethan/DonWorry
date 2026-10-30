package com.donworry.payment.service;

import static com.donworry.payment.service.PaymentRequestConflictException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentRequestAdmissionMariaDbTests {
    @Autowired
    PaymentRequestAdmissionService admission;

    @Autowired
    PaymentOrderRepository orders;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentRequestRepository requests;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    private TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }

    private PaymentRequestCommand command() {
        return new PaymentRequestCommand(
                991L,
                UUID.randomUUID().toString(),
                "customer",
                1000L,
                Instant.now().minusSeconds(1),
                UUID.randomUUID(),
                PaymentEvaluationFixtures.riskSignals());
    }

    private PaymentRequestCommand key(PaymentRequestCommand c, UUID key) {
        return new PaymentRequestCommand(
                c.merchantId(), c.orderRef(), c.customerRef(), c.amount(), c.orderCreatedAt(), key, c.riskSignals());
    }

    private void conflict(PaymentRequestCommand c, PaymentRequestConflictException.Code code) {
        assertThatThrownBy(() -> admission.admit(c))
                .isInstanceOfSatisfying(
                        PaymentRequestConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo(code));
    }

    private long count(PaymentRequestCommand c) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE merchant_id=? AND order_ref=?",
                Long.class,
                c.merchantId(),
                c.orderRef());
    }

    // 같은 키에서는 새 결제를 만들지 않고 저장한 식별자를 반환한다.
    @Test
    void admit_whenRepeated_reusesCommittedIdentifiers() {
        var c = command();
        var first = admission.admit(c);
        var replay = admission.admit(c);
        assertThat(first.created()).isTrue();
        assertThat(replay)
                .isEqualTo(new PaymentRequestAdmission(first.paymentId(), 1L, first.evaluationRequestId(), false));
        assertThat(count(c)).isEqualTo(1);
        assertThat(requests.findByPaymentId(first.paymentId()).orElseThrow().getProcessingStage())
                .isEqualTo(PaymentProcessingStage.RECEIVED);
        assertThat(payments.findById(first.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    // 접수는 독립 커밋되므로 이후 호출자 실패에도 같은 키로 결제를 찾을 수 있다.
    @Test
    void admit_whenCallerRollsBack_preservesCommittedAdmission() {
        var c = command();
        var first = tx().execute(s -> {
            var result = admission.admit(c);
            s.setRollbackOnly();
            return result;
        });
        assertThat(admission.admit(c).paymentId()).isEqualTo(first.paymentId());
        assertThat(count(c)).isEqualTo(1);
    }

    // 키를 다른 내용에 재사용하거나 같은 주문 내용을 바꾸면 기존 기록을 보존하고 거절한다.
    @Test
    void admit_whenPayloadChanges_rejectsKeyAndOrderConflicts() {
        var c = command();
        admission.admit(c);
        var changed = new PaymentRequestCommand(
                c.merchantId(),
                c.orderRef(),
                c.customerRef(),
                2000L,
                c.orderCreatedAt(),
                c.idempotencyKey(),
                c.riskSignals());
        conflict(changed, IDEMPOTENCY_KEY_CONFLICT);
        conflict(key(changed, UUID.randomUUID()), ORDER_CONTENT_CONFLICT);
        conflict(
                new PaymentRequestCommand(
                        c.merchantId(),
                        "other-" + c.orderRef(),
                        c.customerRef(),
                        c.amount(),
                        c.orderCreatedAt(),
                        c.idempotencyKey(),
                        c.riskSignals()),
                IDEMPOTENCY_KEY_CONFLICT);
        assertThat(count(c)).isEqualTo(1);
    }

    // FDS 식별자는 시도에 저장하고, 같은 키의 재전송은 식별자까지 같아야 같은 결제로 본다 (#325).
    @Test
    void admit_withRiskSignals_storesThemAndRejectsChangedReplay() {
        var c = command();
        var signals = PaymentRiskSignals.builder()
                .deviceId("device-1")
                .cardToken("card-token-1")
                .cardDisplayLabel("신한 **** 1234")
                .ipCountryCode("kr")
                .ipAddress("203.0.113.10")
                .buyerEmail("Buyer@Example.com")
                .build();
        var withSignals = new PaymentRequestCommand(
                c.merchantId(),
                c.orderRef(),
                c.customerRef(),
                c.amount(),
                c.orderCreatedAt(),
                c.idempotencyKey(),
                signals);
        var first = admission.admit(withSignals);
        assertThat(requests.findByPaymentId(first.paymentId()).orElseThrow().riskSignals())
                .isEqualTo(new PaymentRiskSignals(
                        "device-1", "card-token-1", "신한 **** 1234", "KR", "203.0.113.10", "buyer@example.com"));
        assertThat(admission.admit(withSignals).paymentId()).isEqualTo(first.paymentId());

        conflict(c, IDEMPOTENCY_KEY_CONFLICT);
        conflict(
                new PaymentRequestCommand(
                        c.merchantId(),
                        c.orderRef(),
                        c.customerRef(),
                        c.amount(),
                        c.orderCreatedAt(),
                        c.idempotencyKey(),
                        signals.toBuilder().cardToken("card-token-2").build()),
                IDEMPOTENCY_KEY_CONFLICT);
        assertThat(count(c)).isEqualTo(1);
    }

    // 확정 거절된 시도에 한해서 기한 안에 새 결제를 만들고 오래된 키는 이전 시도를 가리킨다.
    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"DENIED", "DECLINED"})
    void admit_afterFinalDecline_createsNextAttemptAndPreservesOldKey(PaymentStatus status) {
        var c = command();
        var first = admission.admit(c);
        tx().executeWithoutResult(s -> {
            var payment = payments.findByIdForUpdate(first.paymentId()).orElseThrow();
            if (status == PaymentStatus.DENIED) payment.deny();
            else payment.declineAuthorization();
        });
        var second = admission.admit(key(c, UUID.randomUUID()));
        assertThat(second.attemptNo()).isEqualTo(2);
        assertThat(second.paymentId()).isNotEqualTo(first.paymentId());
        assertThat(second.evaluationRequestId()).isNotEqualTo(first.evaluationRequestId());
        assertThat(admission.admit(c).paymentId()).isEqualTo(first.paymentId());
        assertThat(count(c)).isEqualTo(2);
    }

    // 진행 중·완료·취소·만료 상태는 새 키라도 재결제를 허용하지 않는다.
    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"PENDING", "HELD", "CAPTURED", "VOIDED", "EXPIRED"})
    void admit_withoutFinalDecline_rejectsNewAttempt(PaymentStatus status) {
        var c = command();
        var first = admission.admit(c);
        jdbc.update("UPDATE payments SET status=? WHERE payment_id=?", status.name(), first.paymentId());
        conflict(key(c, UUID.randomUUID()), ORDER_PAYMENT_EXISTS);
        assertThat(count(c)).isEqualTo(1);
        assertThat(orders.findByMerchantIdAndOrderRef(c.merchantId(), c.orderRef())
                        .orElseThrow()
                        .getLastAttemptNo())
                .isEqualTo(1);
    }

    // 기한이 지난 최초 접수는 주문까지 롤백하고 이미 접수한 키의 재조회는 기한과 무관하다.
    @Test
    void admit_whenExpired_rejectsNewAttemptButReplaysExistingKey() {
        var c = command();
        var expired = new PaymentRequestCommand(
                c.merchantId(),
                c.orderRef(),
                c.customerRef(),
                c.amount(),
                Instant.now().minusSeconds(601),
                c.idempotencyKey(),
                c.riskSignals());
        conflict(expired, ORDER_DEADLINE_PASSED);
        assertThat(orders.findByMerchantIdAndOrderRef(c.merchantId(), c.orderRef()))
                .isEmpty();
        var first = admission.admit(c);
        tx().executeWithoutResult(s ->
                payments.findByIdForUpdate(first.paymentId()).orElseThrow().deny());
        // 시간이 흘렀음을 DB의 최초 주문 시각/기한과 재전송 입력 양쪽에 동일하게 반영한다.
        jdbc.update(
                "UPDATE payment_orders SET order_created_at=TIMESTAMPADD(MINUTE,-20,order_created_at), payment_deadline_at=TIMESTAMPADD(MINUTE,-20,payment_deadline_at) WHERE merchant_id=? AND order_ref=?",
                c.merchantId(),
                c.orderRef());
        var old = new PaymentRequestCommand(
                c.merchantId(),
                c.orderRef(),
                c.customerRef(),
                c.amount(),
                c.orderCreatedAt().minusSeconds(1200),
                c.idempotencyKey(),
                c.riskSignals());
        assertThat(admission.admit(old).paymentId()).isEqualTo(first.paymentId());
        conflict(key(old, UUID.randomUUID()), ORDER_DEADLINE_PASSED);
    }

    // 기존 내부 생성 경로의 결제에 새 HTTP 시도를 덧붙이지 않는다.
    @Test
    void admit_whenLegacyPaymentExists_rejectsWithoutNewRows() {
        var c = command();
        tx().executeWithoutResult(s -> payments.save(
                Payment.pending(c.merchantId(), c.customerRef(), c.orderRef(), c.amount(), Instant.now())));
        conflict(c, LEGACY_ORDER_EXISTS);
        assertThat(orders.findByMerchantIdAndOrderRef(c.merchantId(), c.orderRef()))
                .isEmpty();
        assertThat(count(c)).isEqualTo(1);
    }

    // 가맹점이 다르면 같은 주문번호·키라도 독립적인 결제다.
    @Test
    void admit_forDifferentMerchants_isolatesOrdersAndKeys() {
        var c = command();
        var first = admission.admit(c);
        var other = new PaymentRequestCommand(
                992L,
                c.orderRef(),
                c.customerRef(),
                c.amount(),
                c.orderCreatedAt(),
                c.idempotencyKey(),
                c.riskSignals());
        assertThat(admission.admit(other).paymentId()).isNotEqualTo(first.paymentId());
    }

    private Object outcome(PaymentRequestCommand command, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return admission.admit(command);
        } catch (PaymentRequestConflictException e) {
            return e.getCode();
        }
    }

    private List<Object> race(PaymentRequestCommand a, PaymentRequestCommand b) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x = pool.submit(() -> outcome(a, start));
            var y = pool.submit(() -> outcome(b, start));
            start.countDown();
            return List.of(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS));
        }
    }

    // 최초 주문 INSERT가 경합해도 같은 키는 하나의 접수로 수렴한다.
    @Test
    void admit_whenSameKeyRaces_createsOnePayment() throws Exception {
        var c = command();
        var results = race(c, c);
        assertThat(results).allMatch(PaymentRequestAdmission.class::isInstance);
        var a = (PaymentRequestAdmission) results.get(0);
        var b = (PaymentRequestAdmission) results.get(1);
        assertThat(a.paymentId()).isEqualTo(b.paymentId());
        assertThat(a.created()).isNotEqualTo(b.created());
        assertThat(count(c)).isEqualTo(1);
    }

    // 같은 주문의 서로 다른 키가 경합하면 한 시도만 접수한다.
    @Test
    void admit_whenDifferentKeysRace_createsOnePayment() throws Exception {
        var c = command();
        var results = race(c, key(c, UUID.randomUUID()));
        assertThat(results).contains(ORDER_PAYMENT_EXISTS);
        assertThat(results.stream()
                        .filter(PaymentRequestAdmission.class::isInstance)
                        .count())
                .isEqualTo(1);
        assertThat(count(c)).isEqualTo(1);
    }

    // 거절 후 재결제가 경합하면 잠금 대기 이후의 최신 시도·순번을 기준으로 판단한다.
    @Test
    void admit_whenRetryKeysRaceAfterDecline_createsOnlySecondAttempt() throws Exception {
        var c = command();
        var first = admission.admit(c);
        tx().executeWithoutResult(s ->
                payments.findByIdForUpdate(first.paymentId()).orElseThrow().deny());
        var results = race(key(c, UUID.randomUUID()), key(c, UUID.randomUUID()));
        assertThat(results).contains(ORDER_PAYMENT_EXISTS);
        assertThat(results.stream()
                        .filter(PaymentRequestAdmission.class::isInstance)
                        .map(PaymentRequestAdmission.class::cast)
                        .map(PaymentRequestAdmission::attemptNo)
                        .toList())
                .containsExactly(2L);
        assertThat(count(c)).isEqualTo(2);
        assertThat(orders.findByMerchantIdAndOrderRef(c.merchantId(), c.orderRef())
                        .orElseThrow()
                        .getLastAttemptNo())
                .isEqualTo(2);
    }

    // 다른 주문이 같은 키를 쓰면 패한 트랜잭션의 결제·주문·순번을 남기지 않는다.
    @Test
    void admit_whenDifferentOrdersRaceForKey_rollsBackLoser() throws Exception {
        var a = command();
        var b = new PaymentRequestCommand(
                a.merchantId(),
                UUID.randomUUID().toString(),
                a.customerRef(),
                a.amount(),
                a.orderCreatedAt(),
                a.idempotencyKey(),
                a.riskSignals());
        var results = race(a, b);
        assertThat(results).contains(IDEMPOTENCY_KEY_CONFLICT);
        assertThat(count(a) + count(b)).isEqualTo(1);
        assertThat(List.of(a, b).stream()
                        .filter(c -> orders.findByMerchantIdAndOrderRef(c.merchantId(), c.orderRef())
                                .isPresent())
                        .count())
                .isEqualTo(1);
    }
}
