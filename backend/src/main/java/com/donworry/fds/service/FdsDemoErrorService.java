package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 로컬 시연 스크립트가 한 번만 소비할 수 있는 FDS 내부 오류 주입기다.
@Service
@Profile("!prod")
@ConditionalOnProperty(prefix = "fds.demo-error", name = "enabled", havingValue = "true")
public class FdsDemoErrorService {

    private final AtomicReference<FdsDemoErrorScenario> armedScenario = new AtomicReference<>();
    private final FdsEvaluationRepository evaluations;

    public FdsDemoErrorService(FdsEvaluationRepository evaluations) {
        this.evaluations = evaluations;
    }

    public void armNextEvaluation(FdsDemoErrorScenario scenario) {
        armedScenario.set(scenario);
    }

    @Transactional
    public Optional<FdsEvaluationResponse> failNext(FdsEvaluationRequest request) {
        var scenario = armedScenario.getAndSet(null);
        if (scenario == null) return Optional.empty();
        var evaluation = evaluations.saveAndFlush(FdsEvaluation.failed(request, scenario.errorCode(), Instant.now()));
        return Optional.of(new FdsEvaluationResponse(
                evaluation.getEvaluationRequestId(),
                evaluation.getEvaluationId(),
                evaluation.getResult(),
                evaluation.getRiskScore(),
                evaluation.getEvaluatedAt(),
                evaluation.getErrorCode()));
    }
}
