package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.dto.AskQuestionRequest;
import com.donworry.investigation.report.dto.ReportQuestionResponse;
import com.donworry.investigation.report.service.AiReportQuestionService;
import com.donworry.investigation.service.SecurityStaffGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 보고서 뒤 사건 질문 · 답변 (ADR-0086) — 보안 직원만. 열린 사건은 담당자만 (#612)
// 다른 Case의 보고서 404 · 고정 · 실패 보고서 409 · 한도 초과 429 · AI 정지 503. 답을 못 만들어도 200 + status FAILED
@RestController
@RequestMapping("/api/security-cases/{caseId}/reports/{reportId}/questions")
@RequiredArgsConstructor
public class AiReportQuestionController {

    private final AiReportQuestionService questionService;
    private final SecurityStaffGuard securityStaffGuard;

    @PostMapping
    public ReportQuestionResponse ask(
            @PathVariable Long caseId, @PathVariable Long reportId, @RequestBody AskQuestionRequest request) {
        Long staffId = securityStaffGuard.requireSecurityStaffId();
        return questionService.ask(caseId, reportId, staffId, request.question());
    }

    @GetMapping
    public List<ReportQuestionResponse> list(@PathVariable Long caseId, @PathVariable Long reportId) {
        Long staffId = securityStaffGuard.requireSecurityStaffId();
        return questionService.list(caseId, reportId, staffId);
    }
}
