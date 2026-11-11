package com.donworry.investigation.report.repository;

import com.donworry.investigation.report.entity.ReportStatus;

// Case별 최신 보고서 상태 한 줄 — 목록은 상태만 쓰므로 보고서 본문을 읽지 않는다
public record CaseReportStatus(Long caseId, ReportStatus status) {}
