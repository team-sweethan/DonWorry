package com.donworry.investigation.report.entity;

// 사건 질문 한 줄의 상태 (ADR-0086 5절) — RUNNING에서 셋 중 하나로 끝나고 다시 바뀌지 않는다
// ANSWERED: 사실로 답함 · 보고서로 알 수 없음 / DECLINED: 판정 · 범위 밖 질문 거절 / FAILED: 답을 만들지 못함
public enum QuestionStatus {
    RUNNING,
    ANSWERED,
    DECLINED,
    FAILED
}
