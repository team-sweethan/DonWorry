package com.donworry.investigation.report.dto;

import java.util.List;
import java.util.Map;
import lombok.Builder;

// ai-server → Spring 사건 질문 답 (ai-server app/schemas/question.py QuestionResult, ADR-0086)
// 문장 · 사실 · 도구 · 과정은 answer_body에 그대로 저장하므로 모양을 풀지 않고 받는다. 판정 · 상태 변경은 없다
@Builder
public record AiQuestionResult(
        String questionId,
        String status,
        String kind,
        List<Object> sentences,
        List<String> checks,
        List<Object> facts,
        List<Object> toolCalls,
        List<Object> trace,
        Map<String, String> error) {}
