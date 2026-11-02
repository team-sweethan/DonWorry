package com.donworry.investigation.report.service;

import com.donworry.investigation.report.entity.MlScoreValues;
import com.donworry.investigation.report.entity.MlShadowScore;
import com.donworry.investigation.report.entity.MlShadowStatus;
import com.donworry.investigation.report.repository.MlShadowScoreRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// ml_shadow_scores 저장 전용 — ai-server 호출은 트랜잭션 밖에서 하고, 결과는 건마다 새 트랜잭션으로 바로 커밋한다 (02-code 4절)
// MlShadowService 안에서 직접 부르면 @Transactional이 걸리지 않아 별도 빈으로 뺐다 (AiReportWriter 선례)
@Component
@RequiredArgsConstructor
class MlShadowWriter {

    private final MlShadowScoreRepository mlShadowScoreRepository;

    // 같은 결제 · 모델이 이미 있으면(다른 Spring이 먼저 기록) DataIntegrityViolationException — 호출자가 넘긴다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(MlShadowScore score) {
        mlShadowScoreRepository.saveAndFlush(score);
    }

    // 재시도 결과 반영 — 행을 잠그고 읽어, 그사이 다른 Spring이 끝낸 행이면 건드리지 않는다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeedOnRetry(Long id, MlScoreValues values, Instant now) {
        mlShadowScoreRepository
                .findByIdForUpdate(id)
                .filter(MlShadowWriter::isFailed)
                .ifPresent(score -> score.succeedOnRetry(values, now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failOnRetry(Long id, String failureCode, Instant now) {
        mlShadowScoreRepository
                .findByIdForUpdate(id)
                .filter(MlShadowWriter::isFailed)
                .ifPresent(score -> score.failOnRetry(failureCode, now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void skipOnRetry(Long id, String failureCode, Instant now) {
        mlShadowScoreRepository
                .findByIdForUpdate(id)
                .filter(MlShadowWriter::isFailed)
                .ifPresent(score -> score.skipOnRetry(failureCode, now));
    }

    private static boolean isFailed(MlShadowScore score) {
        return score.getStatus() == MlShadowStatus.FAILED;
    }
}
