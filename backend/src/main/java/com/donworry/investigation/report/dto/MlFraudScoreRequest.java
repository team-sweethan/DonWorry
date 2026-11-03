package com.donworry.investigation.report.dto;

import com.donworry.fds.api.FdsMlInput;
import java.util.List;
import lombok.Builder;

// Spring → ai-server ML 채점 요청 (POST /ml/fraud-score, app/schemas/ml.py FraudScoreRequest, #414)
@Builder
public record MlFraudScoreRequest(
        long evaluationId,
        MlFraudScoreTransaction target,
        List<MlFraudScoreTransaction> customerHistory,
        List<MlFraudScoreTransaction> sharedCardHistory) {

    // 이력 상한을 넘은 묶음은 이력이 비어 있어 틀린 확률이 나온다 — 부르는 쪽이 호출 전에 건너뜀(SKIPPED)으로 남긴다
    public static MlFraudScoreRequest from(long evaluationId, FdsMlInput input) {
        if (input.historyOverLimit()) {
            throw new IllegalArgumentException("이력 상한을 넘은 입력은 채점하지 않습니다 evaluationId=" + evaluationId);
        }
        return MlFraudScoreRequest.builder()
                .evaluationId(evaluationId)
                .target(MlFraudScoreTransaction.from(input.target()))
                .customerHistory(input.customerHistory().stream()
                        .map(MlFraudScoreTransaction::from)
                        .toList())
                .sharedCardHistory(input.sharedCardHistory().stream()
                        .map(MlFraudScoreTransaction::from)
                        .toList())
                .build();
    }
}
