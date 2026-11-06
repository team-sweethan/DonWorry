package com.donworry.investigation.service;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.SecurityCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 직원이 사건 상세 화면(보고서 전문 · 과거 사건 비교 포함)을 열 때 (#612)
// 담당 판단을 먼저 해서 거부되면 결제 · 평가 · 보고서를 조회하지 않는다
// 판정 · 재확인 · 보고서 요청 뒤 돌려주는 상세는 열람으로 치지 않는다 (CaseQueryService.detail 직접 사용)
@Service
@RequiredArgsConstructor
public class CaseDetailViewService {

    private final SecurityStaffGuard securityStaffGuard;
    private final SecurityCaseRepository securityCaseRepository;
    private final CaseAccessGuard caseAccessGuard;
    private final CaseQueryService caseQueryService;
    private final StaffAccessRecorder accessRecorder;

    // 읽기 전용이 아니다 — 허용 기록을 같은 트랜잭션에 저장해, 기록이 실패하면 상세도 내보내지 않는다 (ADR-0082 4절)
    @Transactional
    public CaseDetailResponse view(Long caseId) {
        Long staffId = securityStaffGuard.requireSecurityStaffId();
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        caseAccessGuard.requireAccess(securityCase, staffId, StaffAccessType.CASE_DETAIL_VIEW);
        CaseDetailResponse detail = caseQueryService.build(securityCase);
        accessRecorder.recordAllowed(StaffAccess.builder()
                .accessType(StaffAccessType.CASE_DETAIL_VIEW)
                .caseId(caseId)
                .merchantId(securityCase.getMerchantId())
                .build());
        return detail;
    }
}
