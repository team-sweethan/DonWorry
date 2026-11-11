package com.donworry.investigation.report.service;

import com.donworry.investigation.report.client.AiServerProperties;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.AiReportRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 끄는 순간 끝나지 않은 보고서를 같은 버전에서 고정 보고서로 마무리한다 (ADR-0083 결정 5, 가이드라인 "즉시 중단 · 격리")
// 진행 중 시도를 지워 늦게 온 콜백은 무시된다. 설정이 꺼진 서버는 처음부터 고정 보고서라 할 일이 없다
@Slf4j
@Component
@RequiredArgsConstructor
class AiReportStopHandler {

    private final AiReportRepository aiReportRepository;
    private final AiAttemptTracker tracker;
    private final AiInvestigationRunner runner;
    private final AiServerProperties properties;

    @EventListener
    void on(AiSwitchStopped event) {
        if (!properties.enabled()) {
            return;
        }
        for (AiReport report : aiReportRepository.findByStatusIn(List.of(ReportStatus.PENDING, ReportStatus.RUNNING))) {
            try {
                synchronized (tracker.lockFor(report.getId())) {
                    Optional<AiReport> unfinished =
                            aiReportRepository.findById(report.getId()).filter(r -> !r.isFinished());
                    if (unfinished.isEmpty()) {
                        tracker.remove(report.getId());
                    } else {
                        log.info("긴급정지 — 진행 중 보고서를 고정 보고서로 마무리 reportId={}", report.getId());
                        runner.finishFixed(unfinished.get());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("긴급정지 후 보고서 마무리 실패 reportId={}", report.getId(), e);
            }
        }
    }
}
