package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// 화면에 보이는 사건 질문 하나 (ADR-0086 7절) — 실패면 kind · 문장 없이 status FAILED만 (화면이 고정 문장을 보인다)
// 부른 도구 · 과정 기록 · 실패 원인은 저장만 하고 내보내지 않는다
@Builder
public record ReportQuestionResponse(
        Long questionId,
        String question,
        String status,
        String kind,
        List<Object> sentences,
        List<Object> checks,
        List<Object> facts,
        Instant askedAt,
        Instant answeredAt) {}
