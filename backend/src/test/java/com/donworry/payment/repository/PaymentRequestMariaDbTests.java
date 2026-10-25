package com.donworry.payment.repository;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentRequestMariaDbTests {
    @Autowired
    PaymentOrderRepository orders;

    @Autowired
    PaymentRequestRepository requests;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private PaymentRequest create(long merchant, String ref, UUID key) {
        var now = Instant.now();
        var order = orders.save(PaymentOrder.register(merchant, ref, "customer", 100L, now, now));
        var attempt = order.allocateAttempt(now);
        var payment = payments.save(Payment.pending(merchant, "customer", ref, 100L, now));
        return requests.save(PaymentRequest.received(order, payment, attempt, key, UUID.randomUUID()));
    }

    // 저장 평가를 복원하고 다른 결과로 덮어쓰지 않는다
    @Test
    void snapshot_roundTripsAndRejectsConflictingReplay() {
        var key = UUID.randomUUID();
        var created = tx().execute(s -> create(901, UUID.randomUUID().toString(), key));
        var snapshot = new PaymentEvaluationSnapshot(
                created.getEvaluationRequestId(), 123L, "REVIEW", 70, null, Instant.now());
        tx().executeWithoutResult(s -> {
            var request = requests.findByMerchantIdAndIdempotencyKey(901L, key).orElseThrow();
            request.recordEvaluation(snapshot);
            request.completeAuthorization();
        });
        var loaded = requests.findByPaymentId(created.getPaymentId()).orElseThrow();
        assertThat(loaded.evaluationSnapshot()).contains(snapshot);
        assertThat(loaded.getProcessingStage()).isEqualTo(PaymentProcessingStage.AUTHORIZED);
        loaded.recordEvaluation(snapshot);
        loaded.completeAuthorization();
        assertThatIllegalStateException()
                .isThrownBy(() -> loaded.recordEvaluation(new PaymentEvaluationSnapshot(
                        snapshot.requestId(), 124L, "ALLOW", 1, null, snapshot.evaluatedAt())));
        assertThat(requests.findByMerchantIdAndIdempotencyKey(902L, key)).isEmpty();
    }

    // 평가 오류는 보존하되 결제를 거절하거나 승인하지 않는다
    @Test
    void error_isPersistedWithoutAuthorizationOrPaymentDenial() {
        var created = tx().execute(s -> create(901, UUID.randomUUID().toString(), UUID.randomUUID()));
        tx().executeWithoutResult(s -> {
            var request = requests.findByPaymentId(created.getPaymentId()).orElseThrow();
            request.recordEvaluation(new PaymentEvaluationSnapshot(
                    request.getEvaluationRequestId(), 123L, "ERROR", null, "TIMEOUT", Instant.now()));
            assertThatIllegalStateException().isThrownBy(request::completeAuthorization);
        });
        assertThat(requests.findByPaymentId(created.getPaymentId())
                        .orElseThrow()
                        .getProcessingStage())
                .isEqualTo(PaymentProcessingStage.NEEDS_REVIEW);
        assertThat(payments.findById(created.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    // 키 충돌 시 전체 저장을 롤백하고 다른 가맹점 키는 격리한다
    @Test
    void duplicateKey_rollsBackNewOrderAndPayment_butOtherMerchantMayReuseKey() {
        var key = UUID.randomUUID();
        tx().executeWithoutResult(s -> create(901, UUID.randomUUID().toString(), key));
        var ref = UUID.randomUUID().toString();
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> create(901, ref, key)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.findByMerchantIdAndOrderRef(901L, ref)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_ref = ?", Long.class, ref))
                .isZero();
        tx().executeWithoutResult(s -> create(902, ref, key));
        assertThat(requests.findByMerchantIdAndIdempotencyKey(902L, key)).isPresent();
    }

    // 주문 중복과 불완전 평가 및 가맹점 불일치를 DB에서 차단한다
    @Test
    void databaseRejectsDuplicateOrderAndInvalidStageAndMismatchedMerchant() {
        var ref = UUID.randomUUID().toString();
        var created = tx().execute(s -> create(901, ref, UUID.randomUUID()));
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> create(901, ref, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payment_requests SET processing_stage = 'EVALUATED' WHERE request_id = ?",
                        created.getRequestId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payment_requests SET merchant_id = 903 WHERE request_id = ?", created.getRequestId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payment_requests SET attempt_no = 0 WHERE request_id = ?", created.getRequestId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 과거 NULL과 상태 전이 롤백을 보존한다
    @Test
    void legacyMetadataMayBeNull_andTransitionRollbackPreservesIt() {
        var created = tx().execute(s -> create(901, UUID.randomUUID().toString(), UUID.randomUUID()));
        jdbc.update("UPDATE payments SET status_changed_at = NULL WHERE payment_id = ?", created.getPaymentId());
        assertThat(payments.findById(created.getPaymentId()).orElseThrow().getStatusChangedAt())
                .isNull();
        tx().executeWithoutResult(s -> {
            payments.findByIdForUpdate(created.getPaymentId()).orElseThrow().deny();
            s.setRollbackOnly();
        });
        var unchanged = payments.findById(created.getPaymentId()).orElseThrow();
        assertThat(unchanged.getStatusChangedAt()).isNull();
        assertThat(unchanged.getDeclineCode()).isNull();
        tx().executeWithoutResult(s ->
                payments.findByIdForUpdate(created.getPaymentId()).orElseThrow().deny());
        var denied = payments.findById(created.getPaymentId()).orElseThrow();
        assertThat(denied.getStatusChangedAt()).isNotNull();
        assertThat(denied.getDeclineCode()).isEqualTo("FDS_REJECTED");
    }
}
