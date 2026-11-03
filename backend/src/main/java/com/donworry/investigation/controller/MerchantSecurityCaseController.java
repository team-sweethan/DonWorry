package com.donworry.investigation.controller;

import com.donworry.investigation.dto.MerchantCaseDetailResponse;
import com.donworry.investigation.dto.MerchantCaseListResponse;
import com.donworry.investigation.service.MerchantCaseQueryService;
import com.donworry.investigation.service.MerchantViewerGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 고객사 "보안 조치 결과" API — 로그인한 사장님 가맹점의 Case만 읽는다 (fds 정본 02 §2.12 ②, ADR-0019, #205)
// 조회 전용이다. 판정 · 인계는 보안 직원 API(SecurityCaseController)에만 있다
@RestController
@RequestMapping("/api/merchant/security-cases")
@RequiredArgsConstructor
public class MerchantSecurityCaseController {

    private final MerchantCaseQueryService merchantCaseQueryService;
    private final MerchantViewerGuard merchantViewerGuard;

    @GetMapping
    public MerchantCaseListResponse list() {
        return merchantCaseQueryService.list(merchantViewerGuard.requireMerchantId());
    }

    @GetMapping("/{caseId}")
    public MerchantCaseDetailResponse detail(@PathVariable Long caseId) {
        return merchantCaseQueryService.detail(merchantViewerGuard.requireMerchantId(), caseId);
    }
}
