package com.donworry.investigation.report.service;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.repository.AiReportRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.CaseAccessGuard;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI 보고서 인쇄 · PDF 저장 기록 (#623) — 화면이 인쇄 창을 열 때(버튼 · Ctrl+P) 부른다
// 인쇄 자체는 브라우저가 해서 막지 못한다. 누가 어느 사건 보고서를 출력하려 했는지만 남긴다
@Service
@RequiredArgsConstructor
public class AiReportPrintService {

    private final SecurityCaseRepository securityCaseRepository;
    private final AiReportRepository aiReportRepository;
    private final CaseAccessGuard caseAccessGuard;
    private final StaffAccessRecorder accessRecorder;

    // 이 Case의 보고서가 아니면 기록하지 않고 false. 열린 사건은 담당자만 (거부는 CaseAccessGuard가 기록)
    // 허용 기록은 같은 트랜잭션에 저장한다 — recordAllowed는 트랜잭션 안에서만 부를 수 있다
    @Transactional
    public boolean recordPrint(Long caseId, Long reportId, Long staffId) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        caseAccessGuard.requireAccess(securityCase, staffId, StaffAccessType.REPORT_PRINT);
        boolean ofThisCase = aiReportRepository
                .findById(reportId)
                .filter(report -> report.getCaseId().equals(caseId))
                .isPresent();
        if (!ofThisCase) {
            return false;
        }
        accessRecorder.recordAllowed(StaffAccess.builder()
                .accessType(StaffAccessType.REPORT_PRINT)
                .caseId(caseId)
                .merchantId(securityCase.getMerchantId())
                .build());
        return true;
    }
}
