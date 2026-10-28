package com.donworry.investigation.report.dto;

// Spring → ai-server 조사 접수 (ai-server app/schemas/investigation.py와 같게 둔다)
// 사건 개요의 사실 값은 DB가 정본이라 함께 보낸다. ID는 문자열 (ADR-0009 결정 4)
public record AiInvestigationRequest(
        String reportId,
        // 시도마다 새로 만드는 토큰 — 현재 토큰과 같은 결과만 반영한다 (ADR-0009 결정 2)
        String attemptId,
        String caseId,
        int evidenceVersion,
        int reportVersion,
        String merchantId,
        String customerRef,
        String caseCreatedAt) {}
