package com.donworry.investigation.report.client;

import com.donworry.investigation.report.dto.MlFraudScoreResponse;

// POST /ml/fraud-score 결과를 설계 8절 표대로 나눈 값 (#414) — 부르는 쪽은 이 종류만 보고 기록 방법을 정한다
// sealed라 switch에서 한 종류를 빠뜨리면 컴파일이 막힌다
public sealed interface MlScoreOutcome {

    String REQUEST_REJECTED = "ML_REQUEST_REJECTED";
    String INFERENCE_FAILED = "ML_INFERENCE_FAILED";
    String TIMEOUT = "ML_TIMEOUT";

    // 200 — 채점 성공
    record Scored(MlFraudScoreResponse response) implements MlScoreOutcome {}

    // 500 · 응답 시간 초과 — 같은 결제를 다음 주기에 다시 시도한다
    record Retryable(String failureCode) implements MlScoreOutcome {}

    // 422 등 요청 자체가 거절됨 — 다시 해도 같으므로 재시도하지 않는다
    record Rejected(String failureCode) implements MlScoreOutcome {}

    // 연결 실패 · 503 · 서비스 키 · 경로 · 응답 모양 문제 — 결제 탓이 아니라 기록하지 않고 이번 주기를 멈춘다
    record Unavailable(String reason) implements MlScoreOutcome {}
}
