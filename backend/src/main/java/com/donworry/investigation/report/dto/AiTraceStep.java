package com.donworry.investigation.report.dto;

import lombok.Builder;

// 조사 과정 한 단계 (ai-server app/schemas/report.py TraceStep과 같게 둔다, #294)
// 원본 값 · 프롬프트 없이 코드가 만든 요약만 온다
// 같은 String 필드가 많아 순서를 바꿔 넣어도 컴파일이 못 잡는다 — builder로 만든다 (02-code @Builder)
@Builder
public record AiTraceStep(
        String kind, String actor, String title, Long durationMs, String asked, String received, String retryReason) {}
