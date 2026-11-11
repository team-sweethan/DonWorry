package com.donworry.investigation.report.client;

import com.donworry.investigation.report.dto.AiQuestionResult;

// ai-server 질문 호출 결과 (ADR-0086 6절) — 예외 대신 종류로 돌려준다. 실패는 질문 줄을 FAILED로 끝낸다
public sealed interface AiQuestionOutcome {

    // 검사를 통과한 답 (ANSWERED · DECLINED)
    record Answered(AiQuestionResult result) implements AiQuestionOutcome {}

    // ai-server가 답을 만들지 못했거나(code는 ai-server 값), 호출 · 응답이 잘못됨
    record Failed(String code, String reason) implements AiQuestionOutcome {}
}
