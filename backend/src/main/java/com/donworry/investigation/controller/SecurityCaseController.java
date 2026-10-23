package com.donworry.investigation.controller;

import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CaseSummaryResponse;
import com.donworry.investigation.dto.DecisionSubmitRequest;
import com.donworry.investigation.service.CaseDecisionService;
import com.donworry.investigation.service.CaseQueryService;
import com.donworry.investigation.service.CaseReportService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/security-cases")
@RequiredArgsConstructor
public class SecurityCaseController {

    private final CaseQueryService caseQueryService;
    private final CaseDecisionService caseDecisionService;
    private final CaseReportService caseReportService;

    @GetMapping
    public List<CaseSummaryResponse> list(@RequestParam(required = false) Long merchantId) {
        return caseQueryService.list(merchantId);
    }

    @GetMapping("/{caseId}")
    public CaseDetailResponse detail(@PathVariable Long caseId) {
        return caseQueryService.detail(caseId);
    }

    // 판정 커밋 뒤 인계 결과까지 반영된 상세를 돌려준다
    @PostMapping("/{caseId}/decisions")
    public CaseDetailResponse decide(@PathVariable Long caseId, @Valid @RequestBody DecisionSubmitRequest request) {
        caseDecisionService.decide(caseId, request);
        return caseQueryService.detail(caseId);
    }

    // 현재 증거 버전 보고서가 없을 때만 다시 요청하고, 반영된 상세를 돌려준다
    @PostMapping("/{caseId}/reports")
    public CaseDetailResponse requestReport(@PathVariable Long caseId) {
        caseReportService.requestMissing(caseId);
        return caseQueryService.detail(caseId);
    }
}
