package com.donworry.investigation.report.service;

import com.donworry.investigation.report.entity.InsiderReport;
import com.donworry.investigation.report.entity.InsiderReportTrigger;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.InsiderReportRepository;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// ai_insider_reports 저장 전용 (#675) — 저장마다 새 트랜잭션. 사건(P3) 행은 잠그지 않는다
// 같은 계기 판이 이미 있으면 저장하지 않는다. 두 알림이 동시에 같은 판 번호를 잡으면 UNIQUE가 막고
// DataIntegrityViolationException을 던진다 — 호출자가 다시 읽고 다음 번호로 시도한다 (AiReportWriter와 같은 방식)
@Component
@RequiredArgsConstructor
class InsiderReportWriter {

    private final InsiderReportRepository reports;

    // 저장했으면 true, 같은 계기 판이 이미 있으면 false
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean save(
            Long insiderCaseId,
            InsiderReportTrigger trigger,
            Long triggerAlertId,
            ReportStatus status,
            Map<String, Object> body,
            Instant now) {
        if (reports.existsByInsiderCaseIdAndTriggerType(insiderCaseId, trigger)) return false;
        int nextVersion = reports.findMaxReportVersion(insiderCaseId).orElse(0) + 1;
        reports.saveAndFlush(
                InsiderReport.finished(insiderCaseId, nextVersion, trigger, triggerAlertId, status, body, now));
        return true;
    }
}
