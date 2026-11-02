package com.donworry.investigation.report.service;

import com.donworry.fds.api.FdsMlInput;
import com.donworry.fds.api.FdsMlInputReader;
import com.donworry.fds.api.FdsMlTarget;
import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.client.MlScoreOutcome;
import com.donworry.investigation.report.dto.MlFraudScoreRequest;
import com.donworry.investigation.report.dto.MlFraudScoreResponse;
import com.donworry.investigation.report.entity.MlScoreValues;
import com.donworry.investigation.report.entity.MlShadowScore;
import com.donworry.investigation.report.repository.MlShadowScoreRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

// 커밋된 FDS 평가를 ai-server RF로 따로 채점해 Rule 결과와 나란히 남긴다 (ADR-0062, 설계 375 8절)
// 기록만 한다 — 결제 승인 · 보류 · 거절과 Case 판정은 바꾸지 않는다
@Slf4j
@Service
@EnableConfigurationProperties(MlShadowProperties.class)
@RequiredArgsConstructor
class MlShadowService {

    // ai-server MAX_HISTORY_ROWS(app/schemas/ml.py)와 같은 값 — 넘는 고객은 호출하지 않고 건너뜀으로 남긴다
    static final int HISTORY_LIMIT = 2000;

    static final String HISTORY_LIMIT_EXCEEDED = "HISTORY_LIMIT_EXCEEDED";
    static final String INPUT_NOT_FOUND = "INPUT_NOT_FOUND";

    private final FdsMlInputReader fdsMlInputReader;
    private final AiServerClient aiServerClient;
    private final MlShadowScoreRepository mlShadowScoreRepository;
    private final MlShadowWriter writer;
    private final MlShadowProperties properties;

    // 한 주기 — 실패 재시도 먼저, 남은 자리만큼 새 평가. 서버를 쓸 수 없으면 기록 없이 멈추고 다음 주기에 그 자리부터 다시 한다
    public void runOnce(Instant now) {
        String modelVersion = properties.modelVersion();
        List<MlShadowScore> retries = mlShadowScoreRepository.findRetryTargets(
                modelVersion, properties.maxAttempts(), Limit.of(properties.batchSize()));
        for (MlShadowScore retry : retries) {
            if (!score(retry.getEvaluationId(), new RetryRecorder(retry.getId(), now))) {
                return;
            }
        }
        int remaining = properties.batchSize() - retries.size();
        if (remaining <= 0) {
            return;
        }
        // 커서 = 이 모델로 기록한 가장 큰 평가 번호. 결과마다 행이 생기므로 따로 커서를 두지 않는다
        long cursor = mlShadowScoreRepository.findMaxEvaluationId(modelVersion).orElse(0L);
        List<FdsMlTarget> targets =
                fdsMlInputReader.findNewTargets(cursor, now.minus(properties.settleDelay()), remaining);
        for (FdsMlTarget target : targets) {
            if (!score(target.evaluationId(), new NewRecorder(target, now))) {
                return;
            }
        }
    }

    // 평가 하나를 채점해 기록한다 — 이번 주기를 계속해도 되면 true
    private boolean score(long evaluationId, Recorder recorder) {
        Optional<FdsMlInput> input = fdsMlInputReader.findInput(evaluationId, HISTORY_LIMIT);
        if (input.isEmpty()) {
            recorder.skipped(INPUT_NOT_FOUND);
            return true;
        }
        if (input.get().historyOverLimit()) {
            recorder.skipped(HISTORY_LIMIT_EXCEEDED);
            return true;
        }
        MlScoreOutcome outcome = aiServerClient.score(MlFraudScoreRequest.from(evaluationId, input.get()));
        return switch (outcome) {
            case MlScoreOutcome.Scored scored -> recordScored(evaluationId, scored.response(), recorder);
            case MlScoreOutcome.Retryable retryable -> {
                recorder.failed(retryable.failureCode());
                // 시간 초과가 이어지면 스레드 하나를 같이 쓰는 다른 예약 작업이 막히므로 이번 주기는 멈춘다
                if (MlScoreOutcome.TIMEOUT.equals(retryable.failureCode())) {
                    log.warn("ML Shadow 주기 중단 — ai-server 응답 시간 초과 evaluationId={}", evaluationId);
                    yield false;
                }
                yield true;
            }
            case MlScoreOutcome.Rejected rejected -> {
                // 다시 보내도 같으므로 재시도하지 않는다 — 계속 나오면 P2 조회 규칙 확인 필요
                log.warn("ML Shadow 요청 거절 evaluationId={} code={}", evaluationId, rejected.failureCode());
                recorder.failed(rejected.failureCode());
                yield true;
            }
            case MlScoreOutcome.Unavailable unavailable -> {
                log.warn(
                        "ML Shadow 주기 중단 — ai-server 사용 불가 reason={} evaluationId={}",
                        unavailable.reason(),
                        evaluationId);
                yield false;
            }
        };
    }

    private boolean recordScored(long evaluationId, MlFraudScoreResponse response, Recorder recorder) {
        // 다른 모델 결과가 이 모델 이름으로 섞이지 않게 기록하지 않고 멈춘다 (ai-server 모델 파일 확인 필요)
        if (!properties.modelVersion().equals(response.modelVersion())) {
            log.warn(
                    "ML Shadow 주기 중단 — 모델 버전이 설정과 다름 expected={} actual={} evaluationId={}",
                    properties.modelVersion(),
                    response.modelVersion(),
                    evaluationId);
            return false;
        }
        recorder.succeeded(MlScoreValues.builder()
                .fraudProbability(response.fraudProbability())
                .threshold(response.threshold())
                .mlRiskLevel(response.mlRiskLevel())
                .features(response.features())
                .latencyMs(response.latencyMs())
                .build());
        return true;
    }

    // 새 평가는 행을 만들고, 재시도는 기존 FAILED 행을 고친다 — 결과 처리(설계 8절 표)는 score 하나로 둔다
    private interface Recorder {

        void succeeded(MlScoreValues values);

        void failed(String failureCode);

        void skipped(String failureCode);
    }

    private final class NewRecorder implements Recorder {

        private final FdsMlTarget target;
        private final Instant now;

        private NewRecorder(FdsMlTarget target, Instant now) {
            this.target = target;
            this.now = now;
        }

        @Override
        public void succeeded(MlScoreValues values) {
            insert(MlShadowScore.succeeded(target, properties.modelVersion(), values, now));
        }

        @Override
        public void failed(String failureCode) {
            insert(MlShadowScore.failed(target, properties.modelVersion(), failureCode, now));
        }

        @Override
        public void skipped(String failureCode) {
            insert(MlShadowScore.skipped(target, properties.modelVersion(), failureCode, now));
        }

        // 다른 Spring이 같은 결제를 먼저 기록했으면(UNIQUE) 이미 채점된 것으로 보고 넘어간다
        private void insert(MlShadowScore score) {
            try {
                writer.insert(score);
            } catch (DataIntegrityViolationException e) {
                log.info(
                        "ML Shadow 이미 기록됨 — 건너뜀 paymentId={} modelVersion={}",
                        target.paymentId(),
                        properties.modelVersion());
            }
        }
    }

    private final class RetryRecorder implements Recorder {

        private final Long id;
        private final Instant now;

        private RetryRecorder(Long id, Instant now) {
            this.id = id;
            this.now = now;
        }

        @Override
        public void succeeded(MlScoreValues values) {
            writer.succeedOnRetry(id, values, now);
        }

        @Override
        public void failed(String failureCode) {
            writer.failOnRetry(id, failureCode, now);
        }

        @Override
        public void skipped(String failureCode) {
            writer.skipOnRetry(id, failureCode, now);
        }
    }
}
