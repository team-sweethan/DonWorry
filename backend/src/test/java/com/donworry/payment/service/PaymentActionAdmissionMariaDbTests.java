package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.api.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentActionAdmissionMariaDbTests {
    @Autowired
    PaymentActionAdmissionService admission;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentActionRepository actions;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    private TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }

    private long payment(boolean held) {
        return tx().execute(s -> {
            var p = Payment.pending(999L, "action-test", UUID.randomUUID().toString(), 1000L, Instant.now());
            if (held) p.recordAuthorizationSuccess("auth-action", true);
            return payments.save(p).getPaymentId();
        });
    }

    private PaymentActionCommand command(long id, String type) {
        return new PaymentActionCommand(id, id, type, UUID.randomUUID());
    }

    private PaymentActionAdmission admit(PaymentActionCommand command) {
        return tx().execute(s -> admission.admit(command));
    }

    // 저장만 독립 커밋하는 공개 요청 경로를 만들지 않고 호출자 트랜잭션을 요구한다.
    @Test
    void admit_withoutTransaction_fails() {
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> admission.admit(command(1L, "CAPTURE")));
    }

    // 별도 트랜잭션의 재조회에서도 같은 행을 반환하고 반대 조치와 키 변경을 막는다.
    @Test
    void admit_whenRepeatedOrConflicting_reusesOneRow() {
        var c = command(payment(true), "CAPTURE");
        var first = admit(c);
        assertThat(first.created()).isTrue();
        var repeated = admit(c);
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.action().getActionId()).isEqualTo(first.action().getActionId());
        assertThat(repeated.receipt()).isEqualTo(PaymentActionReceipt.pending());
        assertThat(admit(new PaymentActionCommand(c.paymentId(), c.decisionId(), "VOID", c.idempotencyKey()))
                        .receipt()
                        .errorCode())
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(admit(command(c.paymentId(), "VOID")).receipt().errorCode()).isEqualTo("PAYMENT_ACTION_CONFLICT");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_actions WHERE payment_id=?", Integer.class, c.paymentId()))
                .isEqualTo(1);
    }

    // 없는 결제·승인 전 결제를 거절하고 다른 결제의 키·판정 재사용을 차단한다.
    @Test
    void admit_whenInvalidOrCrossPayment_rejectsWithoutSaving() {
        assertThat(admit(command(Long.MAX_VALUE, "CAPTURE")).receipt().errorCode())
                .isEqualTo("PAYMENT_NOT_FOUND");
        var pending = command(payment(false), "CAPTURE");
        assertThat(admit(pending).receipt().errorCode()).isEqualTo("PAYMENT_NOT_HELD");
        assertThat(actions.findByPaymentId(pending.paymentId())).isEmpty();
        var c = command(payment(true), "CAPTURE");
        admit(c);
        long other = payment(true);
        assertThat(admit(new PaymentActionCommand(other, other, "CAPTURE", c.idempotencyKey()))
                        .receipt()
                        .errorCode())
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(admit(new PaymentActionCommand(other, c.decisionId(), "CAPTURE", UUID.randomUUID()))
                        .receipt()
                        .errorCode())
                .isEqualTo("DECISION_ACTION_CONFLICT");
        assertThat(actions.findByPaymentId(other)).isEmpty();
    }

    // 완료된 요청은 현재 결제가 HELD가 아니어도 저장한 성공 결과를 돌려준다.
    @Test
    void admit_afterCompletion_returnsPersistedResult() {
        var c = command(payment(true), "CAPTURE");
        admit(c);
        var at = Instant.parse("2026-10-25T07:00:00.123456789Z");
        tx().executeWithoutResult(s -> {
            payments.findByIdForUpdate(c.paymentId()).orElseThrow();
            actions.findByPaymentId(c.paymentId()).orElseThrow().completeCapture(999L, at);
            // 직원 완료 전이 연결은 다음 단계다. 여기서는 완료된 저장값의 재조회만 검증한다.
            jdbc.update("UPDATE payments SET status='CAPTURED' WHERE payment_id=?", c.paymentId());
        });
        var result = admit(c);
        assertThat(result.receipt()).isEqualTo(PaymentActionReceipt.accepted(999L));
        assertThat(result.created()).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT DATE_FORMAT(completed_at, '%Y-%m-%d %H:%i:%s.%f') FROM payment_actions WHERE payment_id=?",
                        String.class, c.paymentId()))
                .isEqualTo("2026-10-25 07:00:00.123456");
    }

    // 호출자 롤백 시 조치 저장도 사라지고 같은 키로 새 요청을 받을 수 있다.
    @Test
    void admit_whenCallerRollsBack_removesAction() {
        var c = command(payment(true), "CAPTURE");
        tx().executeWithoutResult(s -> {
            admission.admit(c);
            s.setRollbackOnly();
        });
        assertThat(actions.findByPaymentId(c.paymentId())).isEmpty();
        assertThat(admit(c).created()).isTrue();
    }

    // 같은 결제에 동시에 들어온 반대 조치 중 하나만 새 조치를 만든다.
    @Test
    void admit_whenOppositeActionsRace_createsOnlyOne() throws Exception {
        long id = payment(true);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return admit(command(id, "CAPTURE"));
            });
            var b = pool.submit(() -> {
                start.await();
                return admit(command(id, "VOID"));
            });
            start.countDown();
            var results = java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(PaymentActionAdmission::created).count())
                    .isEqualTo(1);
            assertThat(results.stream()
                            .filter(r -> !r.created())
                            .findFirst()
                            .orElseThrow()
                            .receipt()
                            .errorCode())
                    .isEqualTo("PAYMENT_ACTION_CONFLICT");
        }
    }

    // DB에서도 잘못된 결과 조합과 FK·각 UNIQUE를 막아 서비스 검사 우회를 방어한다.
    @Test
    void schema_whenConstraintsViolated_rejectsRows() {
        long id = payment(true);
        var c = command(id, "CAPTURE");
        admit(c);
        long other = payment(true);
        String insert =
                "INSERT INTO payment_actions(payment_id,decision_id,action_type,idempotency_key,status,requested_at) VALUES(?,?,'CAPTURE',?,'PENDING',UTC_TIMESTAMP(6))";
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(
                        () -> jdbc.update(insert, id, other, UUID.randomUUID().toString()));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(
                        () -> jdbc.update(insert, other, id, UUID.randomUUID().toString()));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() ->
                        jdbc.update(insert, other, other, c.idempotencyKey().toString()));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update(
                        insert, Long.MAX_VALUE, other, UUID.randomUUID().toString()));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update("UPDATE payment_actions SET status='SUCCEEDED' WHERE payment_id=?", id));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(
                        () -> jdbc.update("UPDATE payment_actions SET action_type='REFUND' WHERE payment_id=?", id));
    }
}
