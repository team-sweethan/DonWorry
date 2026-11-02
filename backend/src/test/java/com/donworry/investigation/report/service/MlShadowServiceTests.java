package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsMlInput;
import com.donworry.fds.api.FdsMlInputReader;
import com.donworry.fds.api.FdsMlTarget;
import com.donworry.fds.api.FdsMlTransaction;
import com.donworry.fds.api.FdsResult;
import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.client.MlScoreOutcome;
import com.donworry.investigation.report.dto.MlFraudScoreRequest;
import com.donworry.investigation.report.dto.MlFraudScoreResponse;
import com.donworry.investigation.report.entity.MlRiskLevel;
import com.donworry.investigation.report.entity.MlShadowScore;
import com.donworry.investigation.report.entity.MlShadowStatus;
import com.donworry.investigation.report.repository.MlShadowScoreRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// ML Shadow 한 주기(runOnce)를 실제 MariaDB 기록으로 확인한다 — P2 조회와 ai-server만 가짜로 둔다
// 기록은 MlShadowWriter가 바로 커밋하므로 테스트마다 모델 버전 · 번호를 새로 만들어 서로 섞이지 않게 한다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class MlShadowServiceTests {

    // DATETIME(6)은 마이크로초까지만 저장하므로 비교값도 맞춰 둔다
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    static final Instant SETTLED_BEFORE = NOW.minusSeconds(30);

    static final AtomicLong IDS = new AtomicLong(3_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @Autowired
    MlShadowScoreRepository mlShadowScoreRepository;

    @Autowired
    MlShadowWriter writer;

    @Autowired
    EntityManager entityManager;

    final FdsMlInputReader inputReader = mock(FdsMlInputReader.class);

    final AiServerClient aiServerClient = mock(AiServerClient.class);

    final String modelVersion = "test-shadow-" + IDS.incrementAndGet();

    MlShadowService service;

    @BeforeEach
    void setUp() {
        MlShadowProperties properties = MlShadowProperties.builder()
                .batchSize(50)
                .settleDelay(Duration.ofSeconds(30))
                .maxAttempts(3)
                .modelVersion(modelVersion)
                .build();
        service = new MlShadowService(inputReader, aiServerClient, mlShadowScoreRepository, writer, properties);
    }

    // 채점 성공이면 Rule 판정 스냅샷과 ML 확률 · 등급 · 근거를 한 행에 나란히 남긴다
    @Test
    void runOnce_success_savesProbabilityWithRuleSnapshot() {
        FdsMlTarget target = newTarget();
        givenNewTargets(0L, target);
        givenInput(target);
        given(aiServerClient.score(requestFor(target))).willReturn(scored(target, modelVersion));

        service.runOnce(NOW);

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.getPaymentId()).isEqualTo(target.paymentId());
            assertThat(row.getEvaluationId()).isEqualTo(target.evaluationId());
            assertThat(row.getStatus()).isEqualTo(MlShadowStatus.SUCCEEDED);
            assertThat(row.getRuleResult()).isEqualTo(FdsResult.REVIEW);
            assertThat(row.getRuleRiskScore()).isEqualTo(72);
            assertThat(row.getFraudProbability()).isEqualTo(new BigDecimal("0.660000"));
            assertThat(row.getThreshold()).isEqualTo(new BigDecimal("0.300000"));
            assertThat(row.getMlRiskLevel()).isEqualTo(MlRiskLevel.HIGH);
            assertThat(row.getFeatures()).isEqualTo(Map.of("amount", 30000));
            assertThat(row.getLatencyMs()).isEqualTo(31);
            assertThat(row.getAttemptCount()).isEqualTo(1);
            assertThat(row.getFailureCode()).isNull();
            assertThat(row.getScoredAt()).isEqualTo(NOW);
        });
    }

    // ai-server를 쓸 수 없으면 그 결제부터 기록하지 않고 멈추고, 다음 주기에 그 자리부터 다시 채점한다
    @Test
    void runOnce_aiServerDown_noRecord_resumesNextCycle() {
        FdsMlTarget first = newTarget();
        FdsMlTarget second = newTarget();
        FdsMlTarget third = newTarget();
        givenNewTargets(0L, first, second, third);
        givenNewTargets(first.evaluationId(), second, third);
        givenInput(first);
        givenInput(second);
        givenInput(third);
        given(aiServerClient.score(requestFor(first))).willReturn(scored(first, modelVersion));
        given(aiServerClient.score(requestFor(second)))
                .willReturn(new MlScoreOutcome.Unavailable("CONNECTION_FAILED"), scored(second, modelVersion));
        given(aiServerClient.score(requestFor(third))).willReturn(scored(third, modelVersion));

        service.runOnce(NOW);

        assertThat(rows()).extracting(MlShadowScore::getEvaluationId).containsExactly(first.evaluationId());
        verify(aiServerClient, never()).score(requestFor(third));

        service.runOnce(NOW);

        assertThat(rows())
                .extracting(MlShadowScore::getEvaluationId, MlShadowScore::getStatus)
                .containsExactly(
                        tuple(first.evaluationId(), MlShadowStatus.SUCCEEDED),
                        tuple(second.evaluationId(), MlShadowStatus.SUCCEEDED),
                        tuple(third.evaluationId(), MlShadowStatus.SUCCEEDED));
    }

    // 다른 Spring이 같은 결제를 먼저 기록했으면 두 번 기록하지 않고 다음 결제를 이어서 채점한다
    @Test
    void runOnce_alreadyScored_notDuplicated() {
        FdsMlTarget first = newTarget();
        FdsMlTarget second = newTarget();
        writer.insert(MlShadowScore.skipped(first, modelVersion, MlShadowService.HISTORY_LIMIT_EXCEEDED, NOW));
        // 커서를 읽은 뒤 다른 Spring이 끼어든 상황 — 커서와 관계없이 둘 다 새 대상으로 나온다
        given(inputReader.findNewTargets(anyLong(), any(), anyInt())).willReturn(List.of(first, second));
        givenInput(first);
        givenInput(second);
        given(aiServerClient.score(any())).willAnswer(invocation -> {
            MlFraudScoreRequest request = invocation.getArgument(0);
            return request.evaluationId() == first.evaluationId()
                    ? scored(first, modelVersion)
                    : scored(second, modelVersion);
        });

        service.runOnce(NOW);

        assertThat(rows())
                .extracting(MlShadowScore::getEvaluationId, MlShadowScore::getStatus)
                .containsExactly(
                        tuple(first.evaluationId(), MlShadowStatus.SKIPPED),
                        tuple(second.evaluationId(), MlShadowStatus.SUCCEEDED));
    }

    // 일시 오류(500)는 다음 주기에 다시 시도해 성공하면 성공으로 바뀌고, 계속 실패하면 3번까지만 부른다
    // 재시도와 새 평가가 같은 주기에 섞여도 둘 다 처리된다
    @Test
    void runOnce_inferenceFailed_retriesUpToMax() {
        FdsMlTarget alwaysFails = newTarget();
        FdsMlTarget recovers = newTarget();
        FdsMlTarget later = newTarget();
        givenNewTargets(0L, alwaysFails, recovers);
        givenNewTargets(recovers.evaluationId(), later);
        givenInput(alwaysFails);
        givenInput(recovers);
        givenInput(later);
        MlScoreOutcome failed = new MlScoreOutcome.Retryable(MlScoreOutcome.INFERENCE_FAILED);
        given(aiServerClient.score(requestFor(alwaysFails))).willReturn(failed);
        given(aiServerClient.score(requestFor(recovers))).willReturn(failed, scored(recovers, modelVersion));
        given(aiServerClient.score(requestFor(later))).willReturn(scored(later, modelVersion));

        service.runOnce(NOW);
        service.runOnce(NOW);
        service.runOnce(NOW);
        service.runOnce(NOW);

        assertThat(rows())
                .extracting(
                        MlShadowScore::getEvaluationId,
                        MlShadowScore::getStatus,
                        MlShadowScore::getAttemptCount,
                        MlShadowScore::getFailureCode)
                .containsExactly(
                        tuple(alwaysFails.evaluationId(), MlShadowStatus.FAILED, 3, MlScoreOutcome.INFERENCE_FAILED),
                        tuple(recovers.evaluationId(), MlShadowStatus.SUCCEEDED, 2, null),
                        tuple(later.evaluationId(), MlShadowStatus.SUCCEEDED, 1, null));
        assertThat(rows().get(1).getFraudProbability()).isEqualTo(new BigDecimal("0.660000"));
        verify(aiServerClient, times(3)).score(requestFor(alwaysFails));
    }

    // 422는 다시 보내도 같으므로 실패로 한 번만 남기고 다음 주기에 다시 부르지 않는다
    @Test
    void runOnce_requestRejected_notRetried() {
        FdsMlTarget target = newTarget();
        givenNewTargets(0L, target);
        givenInput(target);
        given(aiServerClient.score(requestFor(target)))
                .willReturn(new MlScoreOutcome.Rejected(MlScoreOutcome.REQUEST_REJECTED));

        service.runOnce(NOW);
        service.runOnce(NOW);
        service.runOnce(NOW);

        assertThat(rows())
                .extracting(MlShadowScore::getStatus, MlShadowScore::getAttemptCount, MlShadowScore::getFailureCode)
                .containsExactly(tuple(MlShadowStatus.FAILED, 1, MlScoreOutcome.REQUEST_REJECTED));
        verify(aiServerClient, times(1)).score(requestFor(target));
    }

    // 응답 시간 초과면 그 결제는 실패(시도 1)로 남기고 이번 주기의 나머지 결제는 부르지 않는다
    @Test
    void runOnce_timeout_stopsCycle() {
        FdsMlTarget first = newTarget();
        FdsMlTarget second = newTarget();
        givenNewTargets(0L, first, second);
        givenInput(first);
        givenInput(second);
        given(aiServerClient.score(requestFor(first))).willReturn(new MlScoreOutcome.Retryable(MlScoreOutcome.TIMEOUT));

        service.runOnce(NOW);

        assertThat(rows())
                .extracting(
                        MlShadowScore::getEvaluationId,
                        MlShadowScore::getStatus,
                        MlShadowScore::getAttemptCount,
                        MlShadowScore::getFailureCode)
                .containsExactly(tuple(first.evaluationId(), MlShadowStatus.FAILED, 1, MlScoreOutcome.TIMEOUT));
        verify(aiServerClient, never()).score(requestFor(second));
    }

    // 이력 상한을 넘은 고객은 ai-server를 부르지 않고 건너뜀으로 남겨 커서가 앞으로 간다
    @Test
    void runOnce_historyOverLimit_skippedWithoutCall() {
        FdsMlTarget target = newTarget();
        givenNewTargets(0L, target);
        given(inputReader.findInput(eq(target.evaluationId()), anyInt())).willReturn(Optional.of(input(target, true)));

        service.runOnce(NOW);

        assertThat(rows())
                .extracting(MlShadowScore::getStatus, MlShadowScore::getAttemptCount, MlShadowScore::getFailureCode)
                .containsExactly(tuple(MlShadowStatus.SKIPPED, 0, MlShadowService.HISTORY_LIMIT_EXCEEDED));
        assertThat(mlShadowScoreRepository.findMaxEvaluationId(modelVersion)).contains(target.evaluationId());
        verifyNoInteractions(aiServerClient);
    }

    // 응답 모델 버전이 설정과 다르면 아무것도 기록하지 않고 이번 주기를 멈춘다
    @Test
    void runOnce_modelVersionMismatch_stopsWithoutRecord() {
        FdsMlTarget first = newTarget();
        FdsMlTarget second = newTarget();
        givenNewTargets(0L, first, second);
        givenInput(first);
        givenInput(second);
        given(aiServerClient.score(requestFor(first))).willReturn(scored(first, "rf-v2"));
        given(aiServerClient.score(requestFor(second))).willReturn(scored(second, modelVersion));

        service.runOnce(NOW);

        assertThat(rows()).isEmpty();
        verify(aiServerClient, never()).score(requestFor(second));
    }

    private void givenNewTargets(long cursor, FdsMlTarget... targets) {
        given(inputReader.findNewTargets(eq(cursor), eq(SETTLED_BEFORE), anyInt()))
                .willReturn(List.of(targets));
    }

    private void givenInput(FdsMlTarget target) {
        given(inputReader.findInput(eq(target.evaluationId()), anyInt())).willReturn(Optional.of(input(target, false)));
    }

    private List<MlShadowScore> rows() {
        return entityManager
                .createQuery(
                        "SELECT s FROM MlShadowScore s WHERE s.modelVersion = :modelVersion ORDER BY s.evaluationId",
                        MlShadowScore.class)
                .setParameter("modelVersion", modelVersion)
                .getResultList();
    }

    private static MlFraudScoreRequest requestFor(FdsMlTarget target) {
        return argThat(request -> request != null && request.evaluationId() == target.evaluationId());
    }

    private static MlScoreOutcome scored(FdsMlTarget target, String responseModelVersion) {
        return new MlScoreOutcome.Scored(MlFraudScoreResponse.builder()
                .evaluationId(target.evaluationId())
                .modelVersion(responseModelVersion)
                .fraudProbability(0.66)
                .threshold(0.30000000000000004)
                .mlRiskLevel(MlRiskLevel.HIGH)
                .features(Map.of("amount", 30000))
                .latencyMs(31)
                .build());
    }

    // 이력 상한을 넘으면 P2가 이력을 비워 준다 (FdsMlInput)
    private static FdsMlInput input(FdsMlTarget target, boolean historyOverLimit) {
        return FdsMlInput.builder()
                .target(FdsMlTransaction.builder()
                        .paymentId(target.paymentId())
                        .merchantId(1L)
                        .customerKey("C1")
                        .amount(30_000)
                        .paymentRequestedAt(NOW)
                        .deviceKey("D1")
                        .cardKey("K1")
                        .build())
                .customerHistory(List.of())
                .sharedCardHistory(List.of())
                .historyOverLimit(historyOverLimit)
                .build();
    }

    private static FdsMlTarget newTarget() {
        return FdsMlTarget.builder()
                .evaluationId(IDS.incrementAndGet())
                .paymentId(IDS.incrementAndGet())
                .result(FdsResult.REVIEW)
                .riskScore(72)
                .evaluatedAt(NOW)
                .build();
    }
}
