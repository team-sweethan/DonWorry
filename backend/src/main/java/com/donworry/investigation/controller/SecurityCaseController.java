package com.donworry.investigation.controller;

import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CaseSummaryResponse;
import com.donworry.investigation.dto.DecisionSubmitRequest;
import com.donworry.investigation.service.CaseDecisionService;
import com.donworry.investigation.service.CaseQueryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/security-cases")
@RequiredArgsConstructor
public class SecurityCaseController {

    private final CaseQueryService caseQueryService;
    private final CaseDecisionService caseDecisionService;

    @GetMapping
    public List<CaseSummaryResponse> list() {
        return caseQueryService.list();
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
}
