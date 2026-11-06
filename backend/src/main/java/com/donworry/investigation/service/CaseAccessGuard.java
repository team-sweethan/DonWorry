package com.donworry.investigation.service;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.SecurityCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 열린 사건은 담당 보안 직원만 열람 · 판정한다 (ADR-0082, #612). 판단은 여기 한 곳에서 한다
// 미배정 열린 사건은 아무도 담당이 아니라 막힌다 — 관리자가 [담당 현황]에서 배정한다
// 종료된 사건은 누구나 — 비슷한 과거 사건 비교 화면이 다른 직원의 종료 사건을 연다. 종료 사건의 판정 등은 각 서비스가 막는다
// 역할(보안 직원) 확인은 SecurityStaffGuard가 먼저 한다
@Component
@RequiredArgsConstructor
public class CaseAccessGuard {

    private final SecurityCaseRepository securityCaseRepository;
    private final StaffAccessRecorder accessRecorder;

    // 사건을 따로 읽지 않는 동작(보고서 진행 조회 등)
    public void requireAccess(Long caseId, Long staffId, StaffAccessType accessType) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        requireAccess(securityCase, staffId, accessType);
    }

    // 거부는 호출 쪽이 롤백돼도 남도록 따로 커밋된다 (StaffAccessRecorder.recordDenied)
    public void requireAccess(SecurityCase securityCase, Long staffId, StaffAccessType accessType) {
        if (securityCase.isClosed() || staffId.equals(securityCase.getAssigneeStaffId())) {
            return;
        }
        accessRecorder.recordDenied(
                StaffAccess.builder()
                        .accessType(accessType)
                        .caseId(securityCase.getId())
                        .merchantId(securityCase.getMerchantId())
                        .build(),
                StaffAccessDenyReason.NOT_ASSIGNEE);
        throw new InvestigationException(InvestigationErrorCode.CASE_ASSIGNEE_ONLY);
    }
}
