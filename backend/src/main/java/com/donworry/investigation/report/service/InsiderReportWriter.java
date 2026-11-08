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
// 같은 계기 판이 이미 있거나 사건 생성 판인데 차단 판이 있으면 저장하지 않는다(isCovered). 두 알림이 동시에 같은 판 번호를 잡으면 UNIQUE가 막고
// DataIntegrityViolationException을 던진다 — 호출자가 다시 읽고 다음 번호로 시도한다 (AiReportWriter와 같은 방식)
@Component
@RequiredArgsConstructor
class InsiderReportWriter {

    private final InsiderReportRepository reports;

    // 저장했으면 true, 같은 계기 판(또는 사건 생성 판인데 차단 판)이 이미 있으면 false
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean save(
            Long insiderCaseId,
            InsiderReportTrigger trigger,
            Long triggerAlertId,
            ReportStatus status,
            Map<String, Object> body,
            Instant now) {
        if (isCovered(reports, insiderCaseId, trigger)) return false;
        int nextVersion = reports.findMaxReportVersion(insiderCaseId).orElse(0) + 1;
        reports.saveAndFlush(
                InsiderReport.finished(insiderCaseId, nextVersion, trigger, triggerAlertId, status, body, now));
        return true;
    }

    // 이 계기의 판이 필요 없는지 — 같은 계기 판이 있거나, 사건 생성 판인데 차단 판이 이미 있으면 (#675 Codex 2차 리뷰)
    // 사건 생성 판을 만드는 사이 차단 판이 먼저 저장되면, 늦게 저장된 사건 생성 판이 더 큰 판 번호로 차단 전 사실(정지 아님)을
    // 보여 준다. 화면은 가장 큰 판을 먼저 열므로 관리자가 옛 상태를 본다. 차단 판에는 그때까지의 사실이 모두 있어 건너뛰어도 잃는 것이 없다
    // (차단 경보로 바로 열린 사건이 차단 판 하나만 갖는 것과 같은 모양)
    static boolean isCovered(InsiderReportRepository reports, Long insiderCaseId, InsiderReportTrigger trigger) {
        if (reports.existsByInsiderCaseIdAndTriggerType(insiderCaseId, trigger)) return true;
        return trigger == InsiderReportTrigger.CASE_OPENED
                && reports.existsByInsiderCaseIdAndTriggerType(insiderCaseId, InsiderReportTrigger.EXPORT_BLOCKED);
    }
}
