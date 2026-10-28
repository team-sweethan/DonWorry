package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.CaseStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// ai-server 보고서가 끝나면 (1) 그사이 증거 버전이 올랐으면 다음 버전을 요청하고 (2) Case 상태를 다시 계산한다
// (1)은 ADR-0009 결정 3 "완료 후 비교" — 같은 버전이면 요청이 새 보고서를 만들지 않는다
// (2)가 없으면 보고서는 끝났는데 Case가 "조사 중"에 남아 직원의 판정 대기 목록에서 빠진다
@Slf4j
@Component
@RequiredArgsConstructor
class AiReportFinishedListener {

    private final AiReportService aiReportService;
    private final CaseStatusService caseStatusService;
    private final SecurityCaseRepository securityCaseRepository;

    @EventListener
    void on(AiReportFinished event) {
        Long caseId = event.caseId();
        try {
            securityCaseRepository
                    .findById(caseId)
                    .filter(securityCase -> !securityCase.isClosed())
                    .map(SecurityCase::getEvidenceVersion)
                    .ifPresent(version -> aiReportService.request(caseId, version));
        } catch (RuntimeException e) {
            log.warn("보고서 완료 후 다음 버전 요청 실패 caseId={}", caseId, e);
        }
        try {
            caseStatusService.refreshById(caseId);
        } catch (RuntimeException e) {
            log.warn("보고서 완료 후 Case 상태 갱신 실패 caseId={}", caseId, e);
        }
    }
}
