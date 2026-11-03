package com.donworry.payment.repository;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.*;
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
class PaymentFdsRecoveryMariaDbTests {
    @Autowired
    PaymentFdsRecoveryRepository recoveries;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    private PaymentFdsRecovery create() {
        return new TransactionTemplate(manager).execute(s -> {
            var now = Instant.now();
            var payment = payments.save(
                    Payment.pending(901L, "customer", UUID.randomUUID().toString(), 100L, now));
            return recoveries.saveAndFlush(PaymentFdsRecovery.waitingAnalysis(
                    payment.getPaymentId(),
                    new PaymentEvaluationSnapshot(UUID.randomUUID(), 987654L, "ERROR", null, "TRANSIENT_TEST", now),
                    UUID.randomUUID(),
                    now,
                    now));
        });
    }
    // 여러 NULL 고유 키와 논리 참조를 허용하며 분석을 영속 저장한다
    @Test
    void recovery_roundTripsAnalysisAndAllowsNullableUniqueKeys() {
        var first = create();
        var second = create();
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            var row = recoveries.findByPaymentIdForUpdate(first.getPaymentId()).orElseThrow();
            row.recordAnalysis(
                    new FdsEvaluationFailureAnalyzed(
                            2000000000000L + first.getRecoveryId(),
                            row.getFailureReportId(),
                            row.getOriginalEvaluationRequestId(),
                            row.getOriginalEvaluationRequestId(),
                            row.getPaymentId(),
                            FdsFailureDisposition.REEVALUATION_AVAILABLE,
                            "TRANSIENT_ERROR_RECOVERED",
                            Instant.now()),
                    Instant.now());
        });
        assertThat(recoveries
                        .findByPaymentId(first.getPaymentId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.READY);
        assertThat(recoveries
                        .findByPaymentId(second.getPaymentId())
                        .orElseThrow()
                        .getAnalysisId())
                .isNull();
    }
    // 고유 키와 물리 FK 및 불완전 상태 조합을 DB에서 거절한다
    @Test
    void database_whenInvalidKeysOrState_rejectsWrites() {
        var first = create();
        var second = create();
        for (String column : new String[] {"payment_id", "original_evaluation_request_id", "failure_report_id"}) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE payment_fds_recoveries a JOIN payment_fds_recoveries b ON b.recovery_id = ? SET a."
                                    + column + " = b." + column + " WHERE a.recovery_id = ?",
                            first.getRecoveryId(),
                            second.getRecoveryId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String assignment : new String[] {
            "status = 'READY'",
            "analysis_id = 123",
            "retry_id = UUID()",
            "original_evaluation_id = 0",
            "original_error_code = ' '",
            "payment_id = 9223372036854775807",
            "completed_at = NOW(6)"
        }) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE payment_fds_recoveries SET " + assignment + " WHERE recovery_id = ?",
                            first.getRecoveryId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(recoveries.findById(first.getRecoveryId()).orElseThrow().getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.WAITING_ANALYSIS);
    }
    // 분석 반영 트랜잭션 롤백 시 대기 상태와 원본을 유지한다
    @Test
    void analysis_whenRolledBack_preservesWaitingState() {
        var row = create();
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            recoveries
                    .findByPaymentIdForUpdate(row.getPaymentId())
                    .orElseThrow()
                    .recordAnalysis(
                            new FdsEvaluationFailureAnalyzed(
                                    2000000000000L + row.getRecoveryId(),
                                    row.getFailureReportId(),
                                    row.getOriginalEvaluationRequestId(),
                                    row.getOriginalEvaluationRequestId(),
                                    row.getPaymentId(),
                                    FdsFailureDisposition.MANUAL_REVIEW_REQUIRED,
                                    "MANUAL",
                                    Instant.now()),
                            Instant.now());
            recoveries.flush();
            s.setRollbackOnly();
        });
        assertThat(recoveries.findById(row.getRecoveryId()).orElseThrow().getAnalysisId())
                .isNull();
    }
    // 접수·완료 조합과 선택 고유 키도 실제 MariaDB에서 검사한다
    @Test
    void database_whenWorkIsRecorded_enforcesOptionalKeysAndTerminalState() {
        var first = create();
        var second = create();
        for (var row : new PaymentFdsRecovery[] {first, second}) {
            jdbc.update(
                    "UPDATE payment_fds_recoveries SET analysis_id = ?, disposition = 'REEVALUATION_AVAILABLE', reason_code = 'TRANSIENT', analyzed_at = NOW(6), status = 'READY' WHERE recovery_id = ?",
                    2000000000000L + row.getRecoveryId(),
                    row.getRecoveryId());
            jdbc.update(
                    "UPDATE payment_fds_recoveries SET new_evaluation_request_id = UUID(), retry_id = UUID(), requested_by = 987654, requested_at = NOW(6), status = 'REQUESTED' WHERE recovery_id = ?",
                    row.getRecoveryId());
        }
        for (String column : new String[] {"analysis_id", "new_evaluation_request_id", "retry_id"}) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE payment_fds_recoveries a JOIN payment_fds_recoveries b ON b.recovery_id = ? SET a."
                                    + column + " = b." + column + " WHERE a.recovery_id = ?",
                            first.getRecoveryId(),
                            second.getRecoveryId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String assignment : new String[] {
            "new_evaluation_request_id = original_evaluation_request_id",
            "status = 'COMPLETED'",
            "requested_by = NULL",
            "disposition = 'MANUAL_REVIEW_REQUIRED'",
            "reason_code = NULL"
        }) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE payment_fds_recoveries SET " + assignment + " WHERE recovery_id = ?",
                            first.getRecoveryId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update(
                "UPDATE payment_fds_recoveries SET status = 'COMPLETED', completed_at = NOW(6) WHERE recovery_id = ?",
                first.getRecoveryId());
        assertThat(recoveries.findById(first.getRecoveryId()).orElseThrow().getReasonCode())
                .isEqualTo("TRANSIENT");
        jdbc.update(
                "UPDATE payment_fds_recoveries SET status = 'MANUAL_REVIEW', completed_at = NOW(6) WHERE recovery_id = ?",
                second.getRecoveryId());
        assertThat(recoveries.findById(second.getRecoveryId()).orElseThrow().getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.MANUAL_REVIEW);
    }
}
