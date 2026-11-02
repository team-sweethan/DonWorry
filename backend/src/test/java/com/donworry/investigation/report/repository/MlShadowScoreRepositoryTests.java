package com.donworry.investigation.report.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsMlTarget;
import com.donworry.fds.api.FdsResult;
import com.donworry.investigation.report.entity.MlRiskLevel;
import com.donworry.investigation.report.entity.MlScoreValues;
import com.donworry.investigation.report.entity.MlShadowScore;
import com.donworry.investigation.report.entity.MlShadowStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

// 실제 MariaDB에서 ml_shadow_scores 저장 · 중복 제약 · 커서 · 재시도 조회를 확인한다
// 각 테스트는 트랜잭션 안에서 돌고 끝나면 롤백된다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class MlShadowScoreRepositoryTests {

    // DATETIME(6)은 마이크로초까지만 저장하므로 비교값도 맞춰 둔다
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    // 다른 테스트 기록과 겹치지 않게 큰 번호에서 하나씩 늘려 쓰고, 모델 버전도 테스트마다 새로 만든다
    static final AtomicLong IDS = new AtomicLong(1_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @Autowired
    MlShadowScoreRepository mlShadowScoreRepository;

    @Autowired
    EntityManager entityManager;

    // 같은 결제를 같은 모델로 두 번 기록하면 DB가 막고, 다른 모델 버전은 기록된다
    @Test
    void mlShadowScore_samePaymentAndModelTwice_violatesUniqueConstraint() {
        String modelVersion = newModelVersion();
        FdsMlTarget target = target(newId(), newId());
        mlShadowScoreRepository.save(MlShadowScore.failed(target, modelVersion, "ML_INFERENCE_FAILED", NOW));
        mlShadowScoreRepository.save(MlShadowScore.failed(target, newModelVersion(), "ML_INFERENCE_FAILED", NOW));
        entityManager.flush();

        assertThatThrownBy(() -> mlShadowScoreRepository.saveAndFlush(
                        MlShadowScore.skipped(target, modelVersion, "HISTORY_LIMIT_EXCEEDED", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 성공 기록을 다시 읽으면 Rule 스냅샷 · 확률 · features가 그대로이고, 기준값의 부동소수 오차는 소수 6자리로 정리된다
    @Test
    void mlShadowScore_succeededSavedAndReloaded_keepsValues() {
        long paymentId = newId();
        long evaluationId = newId();
        String modelVersion = newModelVersion();
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("amount", 30000);
        features.put("customerAvgAmountRatio", 1.25);
        features.put("merchantId", "M1");
        MlScoreValues values = MlScoreValues.builder()
                .fraudProbability(0.66)
                .threshold(0.30000000000000004)
                .mlRiskLevel(MlRiskLevel.HIGH)
                .features(features)
                .latencyMs(31)
                .build();
        Long id = mlShadowScoreRepository
                .save(MlShadowScore.succeeded(target(paymentId, evaluationId), modelVersion, values, NOW))
                .getId();
        entityManager.flush();
        entityManager.clear();

        MlShadowScore reloaded = entityManager.find(MlShadowScore.class, id);

        assertThat(reloaded.getPaymentId()).isEqualTo(paymentId);
        assertThat(reloaded.getEvaluationId()).isEqualTo(evaluationId);
        assertThat(reloaded.getModelVersion()).isEqualTo(modelVersion);
        assertThat(reloaded.getStatus()).isEqualTo(MlShadowStatus.SUCCEEDED);
        assertThat(reloaded.getRuleResult()).isEqualTo(FdsResult.ALLOW);
        assertThat(reloaded.getRuleRiskScore()).isEqualTo(35);
        assertThat(reloaded.getFraudProbability()).isEqualTo(new BigDecimal("0.660000"));
        assertThat(reloaded.getThreshold()).isEqualTo(new BigDecimal("0.300000"));
        assertThat(reloaded.getMlRiskLevel()).isEqualTo(MlRiskLevel.HIGH);
        assertThat(reloaded.getFeatures()).isEqualTo(features);
        assertThat(reloaded.getLatencyMs()).isEqualTo(31);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getFailureCode()).isNull();
        assertThat(reloaded.getScoredAt()).isEqualTo(NOW);
        assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
    }

    // 커서는 그 모델 기록 중 가장 큰 평가 번호만 보고, 기록이 없는 모델은 빈 값이다
    @Test
    void findMaxEvaluationId_otherModelVersion_notMixed() {
        String v1 = newModelVersion();
        String v2 = newModelVersion();
        String emptyModel = newModelVersion();
        long base = newId();
        mlShadowScoreRepository.save(MlShadowScore.failed(target(newId(), base + 1), v1, "ML_INFERENCE_FAILED", NOW));
        mlShadowScoreRepository.save(
                MlShadowScore.skipped(target(newId(), base + 4), v1, "HISTORY_LIMIT_EXCEEDED", NOW));
        mlShadowScoreRepository.save(
                MlShadowScore.skipped(target(newId(), base + 9000), v2, "HISTORY_LIMIT_EXCEEDED", NOW));
        entityManager.flush();

        assertThat(mlShadowScoreRepository.findMaxEvaluationId(v1)).contains(base + 4);
        assertThat(mlShadowScoreRepository.findMaxEvaluationId(v2)).contains(base + 9000);
        assertThat(mlShadowScoreRepository.findMaxEvaluationId(emptyModel)).isEmpty();
    }

    // 재시도 대상은 이 모델의 FAILED 중 시도 수가 최대보다 작은 것뿐이고, 오래된 평가부터 나온다
    @Test
    void findRetryTargets_failedUnderMaxAttempts_only() {
        String modelVersion = newModelVersion();
        long base = newId();
        mlShadowScoreRepository.save(MlShadowScore.failed(target(newId(), base + 6), modelVersion, "ML_TIMEOUT", NOW));
        mlShadowScoreRepository.save(
                MlShadowScore.failed(target(newId(), base + 2), modelVersion, "ML_INFERENCE_FAILED", NOW));
        Long exhaustedId = mlShadowScoreRepository
                .save(MlShadowScore.failed(target(newId(), base + 3), modelVersion, "ML_INFERENCE_FAILED", NOW))
                .getId();
        mlShadowScoreRepository.save(MlShadowScore.succeeded(target(newId(), base + 1), modelVersion, values(), NOW));
        mlShadowScoreRepository.save(
                MlShadowScore.skipped(target(newId(), base + 4), modelVersion, "HISTORY_LIMIT_EXCEEDED", NOW));
        mlShadowScoreRepository.save(
                MlShadowScore.failed(target(newId(), base + 5), newModelVersion(), "ML_INFERENCE_FAILED", NOW));
        entityManager.flush();
        // 재시도 횟수를 늘리는 메서드는 실행 조각(Ⓔ-4)에서 만들므로 여기서는 SQL로 3번 다 쓴 상태를 만든다
        entityManager
                .createNativeQuery("UPDATE ml_shadow_scores SET attempt_count = 3 WHERE shadow_score_id = ?")
                .setParameter(1, exhaustedId)
                .executeUpdate();
        entityManager.clear();

        assertThat(mlShadowScoreRepository.findRetryTargets(modelVersion, 3, Limit.of(10)))
                .extracting(MlShadowScore::getEvaluationId)
                .containsExactly(base + 2, base + 6);
    }

    private static FdsMlTarget target(long paymentId, long evaluationId) {
        return FdsMlTarget.builder()
                .evaluationId(evaluationId)
                .paymentId(paymentId)
                .result(FdsResult.ALLOW)
                .riskScore(35)
                .evaluatedAt(NOW)
                .build();
    }

    private static MlScoreValues values() {
        return MlScoreValues.builder()
                .fraudProbability(0.12)
                .threshold(0.3)
                .mlRiskLevel(MlRiskLevel.LOW)
                .features(Map.of("amount", 10000))
                .latencyMs(20)
                .build();
    }

    private static long newId() {
        return IDS.incrementAndGet();
    }

    // 테스트끼리 · 커밋된 다른 기록과 커서가 섞이지 않게 모델 버전을 매번 새로 만든다
    private static String newModelVersion() {
        return "test-" + IDS.incrementAndGet();
    }
}
