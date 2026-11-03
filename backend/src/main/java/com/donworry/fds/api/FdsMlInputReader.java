package com.donworry.fds.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// ML Shadow 채점 입력 조회 (#409, ADR-0062) — 읽기 전용. 결제 판정에는 쓰지 않는다
// 카드 · 기기 · 고객 번호는 응답 안에서만 통하는 별칭으로 내보낸다 (원문은 P2 밖으로 나가지 않는다)
public interface FdsMlInputReader {

    // afterEvaluationId보다 큰 평가 중 settledBefore 전에 평가된 것을 오래된 순으로 최대 limit개
    // 결제마다 처음 나온 비오류 평가만 준다 (재평가는 ERROR에서만 생기므로 이것이 최종 Rule 판정)
    List<FdsMlTarget> findNewTargets(long afterEvaluationId, Instant settledBefore, int limit);

    // 대상 평가의 ML 입력 묶음. 평가가 없거나 ERROR면 빈 값
    Optional<FdsMlInput> findInput(long evaluationId, int historyLimit);
}
