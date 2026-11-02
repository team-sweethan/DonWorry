package com.donworry.investigation.report.repository;

import com.donworry.investigation.report.entity.MlShadowScore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// ML Shadow 기록은 쌓기만 하므로 삭제를 노출하지 않고 스케줄러(Ⓔ-4)가 쓸 저장 · 조회만 둔다
public interface MlShadowScoreRepository extends Repository<MlShadowScore, Long> {

    MlShadowScore save(MlShadowScore score);

    // UNIQUE(payment_id, model_version) 충돌을 저장 시점에 바로 알기 위해 쓴다 (스케줄러가 겹쳐 돌 때)
    MlShadowScore saveAndFlush(MlShadowScore score);

    // 커서 — 이 모델로 기록한 가장 큰 평가 번호. 다른 모델 기록은 보지 않고, 기록이 없으면 빈 값(처음부터)
    @Query("SELECT MAX(s.evaluationId) FROM MlShadowScore s WHERE s.modelVersion = :modelVersion")
    Optional<Long> findMaxEvaluationId(@Param("modelVersion") String modelVersion);

    // 재시도 때 행을 고치기 위해 다시 읽는다
    Optional<MlShadowScore> findById(Long id);

    // 다시 시도할 실패 — 이 모델의 FAILED 중 시도 수가 최대보다 작은 것, 오래된 평가부터
    // 422(ML_REQUEST_REJECTED)는 다시 보내도 같은 요청이라 빼고, 실패 기록으로만 남긴다 (#415)
    @Query("SELECT s FROM MlShadowScore s"
            + " WHERE s.modelVersion = :modelVersion"
            + " AND s.status = com.donworry.investigation.report.entity.MlShadowStatus.FAILED"
            + " AND s.failureCode <> 'ML_REQUEST_REJECTED'"
            + " AND s.attemptCount < :maxAttempts"
            + " ORDER BY s.evaluationId ASC")
    List<MlShadowScore> findRetryTargets(
            @Param("modelVersion") String modelVersion, @Param("maxAttempts") int maxAttempts, Limit limit);
}
