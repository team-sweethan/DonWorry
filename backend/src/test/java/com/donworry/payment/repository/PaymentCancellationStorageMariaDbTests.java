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
class PaymentCancellationStorageMariaDbTests {
    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentCancellationRequestRepository requests;

    @Autowired
    PaymentCancellationRepository cancellations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00.123456Z");

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private Payment payment(long merchant) {
        return payments.save(
                Payment.pending(merchant, "customer", UUID.randomUUID().toString(), 100L, NOW));
    }

    private PaymentCancellationRequest request(Payment payment, UUID key, PaymentCancellationResolution resolution) {
        return requests.save(PaymentCancellationRequest.recordDecision(payment, key, "취소 😀", resolution, null, NOW));
    }

    private PaymentCancellation start(Payment payment, PaymentCancellationRequest request) {
        return cancellations.save(PaymentCancellation.start(
                request, payment, PaymentCancellationOperation.REFUND, 987654321L, NOW.plusSeconds(1)));
    }

    // 가맹점별 키는 격리하고 동일 결제의 여러 요청과 단일 작업을 함께 복원한다
    @Test
    void storage_whenMultipleKeys_roundTripsOneOperationAndIsolatesMerchants() {
        var key = UUID.randomUUID();
        var linkedKey = UUID.randomUUID();
        var paymentId = tx().execute(s -> {
            var payment = payment(901);
            var source = request(payment, key, PaymentCancellationResolution.ACCEPTED);
            start(payment, source).succeed(NOW.plusSeconds(2));
            request(payment, linkedKey, PaymentCancellationResolution.LINKED);
            request(payment(902), key, PaymentCancellationResolution.DEFERRED);
            return payment.getPaymentId();
        });
        var source = requests.findByMerchantIdAndRequestKey(901L, key).orElseThrow();
        var linked = requests.findByMerchantIdAndRequestKey(901L, linkedKey).orElseThrow();
        var work = cancellations.findByPaymentId(paymentId).orElseThrow();
        assertThat(source.getReason()).isEqualTo("취소 😀");
        assertThat(source.getCreatedAt()).isEqualTo(NOW);
        assertThat(linked.getPaymentId()).isEqualTo(paymentId);
        assertThat(work.getSourceRequestId()).isEqualTo(source.getRequestId());
        assertThat(work.getStatus()).isEqualTo(PaymentCancellationStatus.SUCCEEDED);
        assertThat(work.getCompletedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(requests.findByMerchantIdAndRequestKey(902L, key)).isPresent();
        assertThat(requests.findByMerchantIdAndRequestKey(903L, key)).isEmpty();
    }

    // 키 UNIQUE 위반은 패자 트랜잭션을 롤백하고 기존 최초 내용을 유지한다
    @Test
    void storage_whenSameMerchantKeyConflicts_rollsBackLoser() {
        var key = UUID.randomUUID();
        var first = tx().execute(s -> request(payment(901), key, PaymentCancellationResolution.DEFERRED));
        var secondPayment = tx().execute(s -> payment(901));
        assertThatThrownBy(() -> tx().executeWithoutResult(
                                s -> request(secondPayment, key, PaymentCancellationResolution.DEFERRED)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requests.findByMerchantIdAndRequestKey(901L, key)
                        .orElseThrow()
                        .getPaymentId())
                .isEqualTo(first.getPaymentId());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_cancellation_requests WHERE payment_id = ?",
                        Long.class,
                        secondPayment.getPaymentId()))
                .isZero();
    }

    // 미확정과 실패 이후에도 다른 키로 같은 결제의 작업을 추가할 수 없다
    @Test
    void storage_whenUnknownOrFailed_rejectsSecondOperation() {
        var payment = tx().execute(s -> payment(901));
        tx().executeWithoutResult(
                        s -> start(payment, request(payment, UUID.randomUUID(), PaymentCancellationResolution.ACCEPTED))
                                .markUnknown(NOW.plusSeconds(2)));
        assertDuplicateWorkRejected(payment);
        tx().executeWithoutResult(s -> cancellations
                .findByPaymentId(payment.getPaymentId())
                .orElseThrow()
                .fail("DECLINED", NOW.plusSeconds(3)));
        assertDuplicateWorkRejected(payment);
        assertThat(cancellations
                        .findByPaymentId(payment.getPaymentId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(PaymentCancellationStatus.FAILED);
    }

    private void assertDuplicateWorkRejected(Payment payment) {
        var key = UUID.randomUUID();
        assertThatThrownBy(() -> tx().executeWithoutResult(
                                s -> start(payment, request(payment, key, PaymentCancellationResolution.ACCEPTED))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requests.findByMerchantIdAndRequestKey(901L, key)).isEmpty();
    }

    // 복합 FK가 최초 요청의 결제와 작업의 결제 불일치를 DB에서 거절한다
    @Test
    void storage_whenSourcePaymentMismatches_rejectsCompositeForeignKey() {
        var source =
                tx().execute(s -> request(payment(901), UUID.randomUUID(), PaymentCancellationResolution.ACCEPTED));
        var other = tx().execute(s -> payment(901));
        assertThatThrownBy(() -> jdbc.update(
                        """
                INSERT INTO payment_cancellations
                (payment_id, source_request_id, operation, amount, status, accepted_at, updated_at)
                VALUES (?, ?, 'VOID', 100, 'PROCESSING', ?, ?)
                """,
                        other.getPaymentId(),
                        source.getRequestId(),
                        java.sql.Timestamp.from(NOW),
                        java.sql.Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // NULL 필수값 누락이나 모순된 결과 조합이 CHECK의 UNKNOWN을 통해 통과하지 않는다
    @Test
    void storage_whenInvalidStateCombinations_rejectsChecks() {
        var request =
                tx().execute(s -> request(payment(901), UUID.randomUUID(), PaymentCancellationResolution.DEFERRED));
        for (var assignment : new String[] {
            "response_code = NULL",
            "response_code = 'OTHER'",
            "resolution = 'INVALID'",
            "resolution = 'ACCEPTED'",
            "reason = ' '",
            "merchant_id = 0",
            "updated_at = '2020-01-01'"
        }) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE payment_cancellation_requests SET " + assignment + " WHERE request_id = ?",
                            request.getRequestId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        var work = tx().execute(s -> {
            var payment = payment(901);
            return start(payment, request(payment, UUID.randomUUID(), PaymentCancellationResolution.ACCEPTED));
        });
        for (var assignment : new String[] {
            "capture_id = NULL",
            "capture_id = 0",
            "operation = 'VOID'",
            "status = 'SUCCEEDED'",
            "status = 'FAILED'",
            "status = 'INVALID'",
            "amount = 0",
            "error_code = 'ERROR'",
            "updated_at = '2020-01-01'",
            "status = 'FAILED', completed_at = updated_at, error_code = NULL",
            "status = 'FAILED', completed_at = updated_at, error_code = ' '",
            "status = 'SUCCEEDED', completed_at = '2020-01-01'",
            "status = 'SUCCEEDED', completed_at = '2030-01-01'"
        }) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE payment_cancellations SET " + assignment + " WHERE cancellation_id = ?",
                            work.getCancellationId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // 저장 기반만 검증하며 롤백한 작업이 접수된 것처럼 남지 않는다
    @Test
    void storage_whenTransactionRollsBack_preservesDeferredRequest() {
        var key = UUID.randomUUID();
        var payment = tx().execute(s -> payment(901));
        tx().executeWithoutResult(s -> request(payment, key, PaymentCancellationResolution.DEFERRED));
        tx().executeWithoutResult(s -> {
            var request = requests.findByMerchantIdAndRequestKey(901L, key).orElseThrow();
            request.reconsider(payment, PaymentCancellationResolution.ACCEPTED, null, NOW.plusSeconds(1));
            start(payment, request);
            s.setRollbackOnly();
        });
        var request = requests.findByMerchantIdAndRequestKey(901L, key).orElseThrow();
        assertThat(request.getResolution()).isEqualTo(PaymentCancellationResolution.DEFERRED);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
        assertThat(cancellations.findByPaymentId(payment.getPaymentId())).isEmpty();
    }
}
