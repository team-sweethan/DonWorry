package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.service.AiReportPrintService;
import com.donworry.investigation.service.SecurityStaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// AI 보고서 인쇄 · PDF 저장 기록 (#623) — 보안 직원만. 열린 사건은 담당자만 (#612)
// 다른 Case의 보고서 번호는 404
@RestController
@RequestMapping("/api/security-cases/{caseId}/reports/{reportId}/print")
@RequiredArgsConstructor
public class AiReportPrintController {

    private final AiReportPrintService printService;
    private final SecurityStaffGuard securityStaffGuard;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void print(@PathVariable Long caseId, @PathVariable Long reportId) {
        Long staffId = securityStaffGuard.requireSecurityStaffId();
        if (!printService.recordPrint(caseId, reportId, staffId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이 Case의 AI 보고서가 아닙니다.");
        }
    }
}
