package com.donworry.investigation.service;

import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.ReportGateway.ReportSnapshot;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 현재 증거 버전의 보고서가 없을 때 직원이 다시 요청한다
// 예: 목 보고서가 서버 재시작으로 비었거나, 커밋 뒤 보고서 요청이 실패해 EVENT_PUBLICATION에 미완료로 남은 경우
// 보고서 요청은 외부 호출이라 트랜잭션 밖에서 한다 (02-code 4절)
@Service
@RequiredArgsConstructor
public class CaseReportService {

    private final SecurityCaseRepository securityCaseRepository;
    private final ReportGateway reportGateway;
    private final CaseStatusService caseStatusService;
    private final SecurityStaffGuard securityStaffGuard;
    private final CaseAccessGuard caseAccessGuard;

    // 이미 현재 버전을 조사 중이거나 끝났으면 다시 요청하지 않는다. 종료된 Case는 그대로 둔다
    // 열린 사건은 담당자만 (#612) — 응답으로 상세가 나가므로 상세 열람과 같은 판단을 한다
    public void requestMissing(Long caseId) {
        Long staffId = securityStaffGuard.requireSecurityStaffId();
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        caseAccessGuard.requireAccess(securityCase, staffId, StaffAccessType.REPORT_REQUEST);
        if (securityCase.isClosed()) {
            return;
        }
        int evidenceVersion = securityCase.getEvidenceVersion();
        Optional<ReportSnapshot> latest = reportGateway.latest(caseId);
        if (latest.isPresent() && latest.get().evidenceVersion() >= evidenceVersion) {
            return;
        }
        reportGateway.request(caseId, evidenceVersion);
        caseStatusService.refreshById(caseId);
    }
}
