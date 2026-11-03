package com.donworry.investigation.report.dto;

import java.util.List;

// 생성 중인 보고서의 끝난 단계 (#322) — RUNNING이 아니거나 ai-server를 끄거나 조회하지 못하면 steps는 빈 목록
// ID는 P4 화면 응답 규칙대로 문자열 (ADR-0009 결정 4)
public record ReportProgressResponse(String reportId, String status, List<AiTraceStep> steps) {}
