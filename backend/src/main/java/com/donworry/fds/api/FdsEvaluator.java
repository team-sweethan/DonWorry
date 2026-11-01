package com.donworry.fds.api;

// P5는 이 공개 인터페이스만 주입받아 P2 평가를 동기 호출한다.
public interface FdsEvaluator {

    FdsEvaluationResponse evaluate(FdsEvaluationRequest request);

    default FdsEvaluationResponse evaluateHistoricalSnapshot(FdsEvaluationRequest request) {
        return evaluate(request);
    }
}
