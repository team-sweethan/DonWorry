package com.donworry.investigation.report.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 서버가 켜지면 열린 내부자 사건에 빠진 해제 검토 보고서 판을 채운다 (#675 Codex 리뷰)
// 보고서 기능 배포 전에 열린 사건 · 이미 정지돼 새 계기가 생길 수 없는 직원의 사건도 판정 전에 보고서를 볼 수 있게 한다
// 이미 있는 계기는 건너뛰어 켤 때마다 돌아도 같다. 실패해도 서버 시작은 막지 않는다 — 다음 시작 때 다시 채운다
// 사건 묶기(InsiderCaseBackfill)가 같은 때 새 사건을 열어도 판은 계기별 UNIQUE로 하나만 남는다
@Slf4j
@Component
@RequiredArgsConstructor
class InsiderReportBackfill {

    private final InsiderReportService insiderReportService;

    @EventListener(ApplicationReadyEvent.class)
    void fillOpenCases() {
        try {
            int requested = insiderReportService.backfillOpenCases();
            if (requested > 0) log.info("열린 내부자 사건의 빠진 해제 검토 보고서를 채움 requested={}", requested);
        } catch (RuntimeException e) {
            log.warn("열린 내부자 사건의 해제 검토 보고서를 채우지 못함 — 다음 시작 때 다시 시도", e);
        }
    }
}
