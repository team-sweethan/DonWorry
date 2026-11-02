package com.donworry.investigation.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.fds.api.FdsMlTarget;
import com.donworry.fds.api.FdsResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

// ml_shadow_scores 기록의 재시도 규칙 — DB 없이 엔티티만 확인한다
class MlShadowScoreTests {

    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    static final Instant LATER = NOW.plusSeconds(30);

    // 끝난 기록(SUCCEEDED · SKIPPED)은 재시도 메서드로 바뀌지 않고 값 · 시도 수 · 갱신 시각이 그대로다
    @Test
    void mlShadowScore_retryOnFinished_rejected() {
        MlShadowScore succeeded = MlShadowScore.succeeded(target(), "rf-v1", values(0.66), NOW);
        MlShadowScore skipped = MlShadowScore.skipped(target(), "rf-v1", "HISTORY_LIMIT_EXCEEDED", NOW);

        assertThatThrownBy(() -> succeeded.failOnRetry("ML_TIMEOUT", LATER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> succeeded.succeedOnRetry(values(0.12), LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> skipped.succeedOnRetry(values(0.12), LATER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> skipped.skipOnRetry("INPUT_NOT_FOUND", LATER))
                .isInstanceOf(IllegalStateException.class);

        assertThat(succeeded.getStatus()).isEqualTo(MlShadowStatus.SUCCEEDED);
        assertThat(succeeded.getFraudProbability()).isEqualTo(new BigDecimal("0.660000"));
        assertThat(succeeded.getFailureCode()).isNull();
        assertThat(succeeded.getAttemptCount()).isEqualTo(1);
        assertThat(succeeded.getUpdatedAt()).isEqualTo(NOW);
        assertThat(skipped.getStatus()).isEqualTo(MlShadowStatus.SKIPPED);
        assertThat(skipped.getFailureCode()).isEqualTo("HISTORY_LIMIT_EXCEEDED");
        assertThat(skipped.getFraudProbability()).isNull();
        assertThat(skipped.getAttemptCount()).isZero();
        assertThat(skipped.getUpdatedAt()).isEqualTo(NOW);
    }

    private static FdsMlTarget target() {
        return FdsMlTarget.builder()
                .evaluationId(10L)
                .paymentId(20L)
                .result(FdsResult.ALLOW)
                .riskScore(35)
                .evaluatedAt(NOW)
                .build();
    }

    private static MlScoreValues values(double fraudProbability) {
        return MlScoreValues.builder()
                .fraudProbability(fraudProbability)
                .threshold(0.3)
                .mlRiskLevel(MlRiskLevel.HIGH)
                .features(Map.of("amount", 30000))
                .latencyMs(31)
                .build();
    }
}
