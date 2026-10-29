package com.donworry.investigation.report.dto;

import java.util.List;

// 생성 중인 보고서의 끝난 단계 (#322) — RUNNING이 아니거나 ai-server를 끄거나 조회하지 못하면 steps는 빈 목록
public record ReportProgressResponse(Long reportId, String status, List<AiTraceStep> steps) {}
