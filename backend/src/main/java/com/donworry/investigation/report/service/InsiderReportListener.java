package com.donworry.investigation.report.service;

import com.donworry.investigation.service.InsiderCaseChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

// P3 내부자 사건이 열리거나 차단 경보가 붙은 것이 커밋된 뒤 해제 검토 보고서를 만든다 (#675)
// 트랜잭션 없이 받는다 — 기본 REQUIRES_NEW면 사실 읽기 하나의 실패가 바깥 트랜잭션을 롤백 전용으로 만들어 실패 판 저장까지 막는다.
// 읽기 · 저장은 각자 트랜잭션(저장은 InsiderReportWriter). 같은 알림이 다시 와도 판은 하나
// 사실 읽기가 실패하면 예외가 나가 발행이 실패로 남고, InsiderReportRedeliveryScheduler가 다시 보낸다 (#702)
@Component
@RequiredArgsConstructor
class InsiderReportListener {

    private final InsiderReportService insiderReportService;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(InsiderCaseChanged event) {
        insiderReportService.generate(event);
    }
}
