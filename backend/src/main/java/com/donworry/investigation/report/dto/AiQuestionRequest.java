package com.donworry.investigation.report.dto;

import java.util.List;
import java.util.Map;
import lombok.Builder;

// Spring → ai-server 사건 질문 요청 (ai-server app/schemas/question.py QuestionRequest, ADR-0086)
// ID는 ai-server 계약대로 문자열로 보낸다. reportBody는 저장된 보고서 본문 그대로
@Builder
public record AiQuestionRequest(
        String questionId,
        String reportId,
        String caseId,
        int reportEvidenceVersion,
        int currentEvidenceVersion,
        String question,
        Map<String, Object> reportBody,
        List<PreviousTurn> previous) {

    // 직전 질문 · 답 (같은 보고서 · 같은 직원, 끝난 답만, 최대 3개) — 저장된 answer_body에서 그대로 옮긴다
    @Builder
    public record PreviousTurn(String question, String kind, List<Object> sentences, List<Object> facts) {}
}
