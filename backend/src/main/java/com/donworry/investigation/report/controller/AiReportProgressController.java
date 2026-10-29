package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.dto.ReportProgressResponse;
import com.donworry.investigation.report.service.AiReportProgressService;
import com.donworry.investigation.service.SecurityStaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// 사건 상세의 AI 보고서 진행 단계 (#322) — 보안 직원만, 화면의 기존 자동 조회(3초 · 2분 제한)에 맞춰 불린다
// ai-server를 끄면 빈 목록을 준다. 다른 Case의 보고서 번호는 404
@RestController
@RequestMapping("/api/security-cases/{caseId}/reports/{reportId}/progress")
@RequiredArgsConstructor
public class AiReportProgressController {

    private final AiReportProgressService progressService;
    private final SecurityStaffGuard securityStaffGuard;

    @GetMapping
    public ReportProgressResponse progress(@PathVariable Long caseId, @PathVariable Long reportId) {
        securityStaffGuard.requireSecurityStaffId();
        return progressService
                .progress(caseId, reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이 Case의 AI 보고서가 아닙니다."));
    }
}
