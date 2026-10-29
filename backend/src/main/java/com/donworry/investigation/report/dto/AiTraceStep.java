package com.donworry.investigation.report.dto;

// 조사 과정 한 단계 (ai-server app/schemas/report.py TraceStep과 같게 둔다, #294)
// 원본 값 · 프롬프트 없이 코드가 만든 요약만 온다
public record AiTraceStep(
        String kind, String actor, String title, Long durationMs, String asked, String received, String retryReason) {}
