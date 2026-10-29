package com.donworry.investigation.controller;

import com.donworry.investigation.dto.CaseCountsResponse;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CasePaymentLinkResponse;
import com.donworry.investigation.dto.CaseSummaryResponse;
import com.donworry.investigation.dto.DecisionSubmitRequest;
import com.donworry.investigation.service.CaseDecisionService;
import com.donworry.investigation.service.CaseQueryService;
import com.donworry.investigation.service.CaseReportService;
import com.donworry.investigation.service.HandoffRecheckService;
import com.donworry.investigation.service.SecurityStaffGuard;
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

// 보안 직원 Case API — 모든 경로는 로그인한 보안 직원만 쓴다 (ADR-0019)
// 조회 · 보고서 재요청은 여기서 확인하고, 판정 · 다시 확인은 서비스가 판정 직원을 정하면서 확인한다
@RestController
@RequestMapping("/api/security-cases")
@RequiredArgsConstructor
public class SecurityCaseController {

    private final CaseQueryService caseQueryService;
    private final CaseDecisionService caseDecisionService;
    private final CaseReportService caseReportService;
    private final HandoffRecheckService handoffRecheckService;
    private final SecurityStaffGuard securityStaffGuard;

    // 실제 P5 결제 · P2 평가 근거를 내보내므로 보안 직원만 본다. 보안 직원은 모든 가맹점 Case를 보므로 merchantId는 표시 필터다
    @GetMapping
    public List<CaseSummaryResponse> list(@RequestParam(required = false) Long merchantId) {
        securityStaffGuard.requireSecurityStaffId();
        return caseQueryService.list(merchantId);
    }

    // 사이드바 배지용 상태별 건수. 사용자 동작(메뉴 이동 · 탭 복귀 · 판정) 때만 부른다 — 타이머로 부르면 세션 무활동 만료가 오지 않는다
    @GetMapping("/counts")
    public CaseCountsResponse counts() {
        securityStaffGuard.requireSecurityStaffId();
        return caseQueryService.counts();
    }

    // 결제 ID(여러 개) → 연결된 Case. 경보 화면이 Case로 이동할 때 쓴다 (#309)
    @GetMapping("/payment-links")
    public List<CasePaymentLinkResponse> paymentLinks(@RequestParam(required = false) List<Long> paymentIds) {
        securityStaffGuard.requireSecurityStaffId();
        return caseQueryService.paymentLinks(paymentIds);
    }

    @GetMapping("/{caseId}")
    public CaseDetailResponse detail(@PathVariable Long caseId) {
        securityStaffGuard.requireSecurityStaffId();
        return caseQueryService.detail(caseId);
    }

    // 판정 커밋 뒤 인계 결과까지 반영된 상세를 돌려준다
    @PostMapping("/{caseId}/decisions")
    public CaseDetailResponse decide(@PathVariable Long caseId, @Valid @RequestBody DecisionSubmitRequest request) {
        caseDecisionService.decide(caseId, request);
        return caseQueryService.detail(caseId);
    }

    // 금융 처리 대기 중인 인계를 같은 멱등키로 다시 확인하고, 반영된 상세를 돌려준다 (P6 매입 재실행 아님)
    @PostMapping("/{caseId}/handoffs/{handoffId}/recheck")
    public CaseDetailResponse recheckHandoff(@PathVariable Long caseId, @PathVariable Long handoffId) {
        handoffRecheckService.recheck(caseId, handoffId);
        return caseQueryService.detail(caseId);
    }

    // 현재 증거 버전 보고서가 없을 때만 다시 요청하고, 반영된 상세를 돌려준다
    @PostMapping("/{caseId}/reports")
    public CaseDetailResponse requestReport(@PathVariable Long caseId) {
        securityStaffGuard.requireSecurityStaffId();
        caseReportService.requestMissing(caseId);
        return caseQueryService.detail(caseId);
    }
}
