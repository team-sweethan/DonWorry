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
        evaluations.findById(evaluationId).orElseThrow().recordAuthorizationResult(result.name());
    }
}
