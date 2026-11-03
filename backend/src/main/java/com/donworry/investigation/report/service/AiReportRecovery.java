package com.donworry.investigation.report.service;

import com.donworry.investigation.service.CaseStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 서버가 켜지면 재시작 전에 끝나지 못한 보고서를 마무리하고, 그 Case 상태를 다시 계산한다 (ADR-0009 한계)
// CaseStatusService가 ReportGateway(AiReportService)에 의존하므로 순환을 피하려고 AiReportService 밖에 둔다
@Slf4j
@Component
@RequiredArgsConstructor
class AiReportRecovery {

    private final AiReportService aiReportService;
    private final CaseStatusService caseStatusService;

    @EventListener(ApplicationReadyEvent.class)
    void resumeUnfinished() {
        for (Long caseId : aiReportService.resumeUnfinished()) {
            try {
                caseStatusService.refreshById(caseId);
            } catch (RuntimeException e) {
                log.warn("보고서 재개 후 Case 상태 갱신 실패 caseId={}", caseId, e);
            }
        }
    }
}
