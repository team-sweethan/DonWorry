package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;

import com.donworry.fds.api.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentFdsRecoveryTests {
    private final Instant now = Instant.parse("2026-10-28T00:00:00.123456789Z");
    private final UUID original = UUID.randomUUID();
    private final UUID report = UUID.randomUUID();

    private PaymentFdsRecovery recovery() {
        return PaymentFdsRecovery.waitingAnalysis(
                1L,
                new PaymentEvaluationSnapshot(original, 2L, "ERROR", null, "TRANSIENT_TEST", now),
                report,
                now,
                now);
    }

    private FdsEvaluationFailureAnalyzed event(FdsFailureDisposition disposition, String reason) {
        return new FdsEvaluationFailureAnalyzed(3L, report, original, original, 1L, disposition, reason, now);
    }
    // 같은 분석은 최초 사유와 시각을 보존하고 충돌 분석은 거절한다
    @Test
    void analysis_whenReplayed_preservesOriginalAndRejectsConflict() {
        var recovery = recovery();
        var event = event(FdsFailureDisposition.MANUAL_REVIEW_REQUIRED, "MANUAL");
        recovery.recordAnalysis(event, now);
        recovery.recordAnalysis(event, now.plusSeconds(10));
        assertThat(recovery.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.MANUAL_REVIEW);
        assertThat(recovery.getCompletedAt()).isEqualTo(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThatIllegalStateException()
                .isThrownBy(() ->
                        recovery.recordAnalysis(event(FdsFailureDisposition.REEVALUATION_AVAILABLE, "OTHER"), now));
        assertThat(recovery.getReasonCode()).isEqualTo("MANUAL");
    }
    // 재평가 가능 분석은 작업 키나 완료 시각을 만들지 않는다
    @Test
    void analysis_whenAvailable_onlyBecomesReady() {
        var recovery = recovery();
        recovery.recordAnalysis(event(FdsFailureDisposition.REEVALUATION_AVAILABLE, "TRANSIENT_ERROR_RECOVERED"), now);
        assertThat(recovery.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.READY);
        assertThat(recovery.getRetryId()).isNull();
        assertThat(recovery.getCompletedAt()).isNull();
        assertThat(recovery.getOriginalErrorCode()).isEqualTo("TRANSIENT_TEST");
    }
    // 다른 평가 계보와 정상 결과는 복구 기록으로 수용하지 않는다
    @Test
    void recovery_whenWrongLineageOrNormalResult_rejectsWithoutMutation() {
        var recovery = recovery();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> recovery.recordAnalysis(
                        new FdsEvaluationFailureAnalyzed(
                                3L,
                                report,
                                original,
                                UUID.randomUUID(),
                                1L,
                                FdsFailureDisposition.REEVALUATION_AVAILABLE,
                                "TEST",
                                now),
                        now));
        assertThat(recovery.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.WAITING_ANALYSIS);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentFdsRecovery.waitingAnalysis(
                        1L, new PaymentEvaluationSnapshot(original, 2L, "ALLOW", 0, null, now), report, now, now));
    }
}
