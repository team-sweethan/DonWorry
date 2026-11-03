package com.donworry.fds.service;

import com.donworry.fds.api.FdsAuthorizationResultRecorder;
import com.donworry.fds.repository.FdsEvaluationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class FdsAuthorizationResultService implements FdsAuthorizationResultRecorder {
    private final FdsEvaluationRepository evaluations;

    @Override
    @Transactional
    public void record(Long evaluationId, AuthorizationResult result) {
        if (evaluationId == null || result == null) throw new IllegalArgumentException("evaluation result is required");
        // P5 승인 완료를 P2 이력 기록 실패로 롤백하지 않는다. 실제 P2 평가가 저장된 경우에만 R08용 결과를 보존한다.
        evaluations.findById(evaluationId).ifPresent(evaluation -> evaluation.recordAuthorizationResult(result.name()));
    }
}
