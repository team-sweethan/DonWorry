package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.dto.InsiderReportResponse;
import com.donworry.investigation.report.service.InsiderReportService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// ADMIN 내부자 사건 해제 검토 보고서 (#675) — 판 목록. 보고서가 아직 없으면 빈 목록
// 역할은 SecurityConfig가 막고, 서비스가 SecurityStaffGuard.requireAdmin으로 한 번 더 확인한다
@RestController
@RequestMapping("/api/admin/insider-cases/{insiderCaseId}/reports")
@RequiredArgsConstructor
public class AdminInsiderReportController {

    private final InsiderReportService insiderReportService;

    @GetMapping
    public List<InsiderReportResponse> list(@PathVariable Long insiderCaseId) {
        return insiderReportService.list(insiderCaseId);
    }
}
