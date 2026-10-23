package com.donworry.investigation.service;

import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import com.donworry.investigation.service.InvestigationEvents.CaseEvidenceChanged;
import com.donworry.investigation.service.InvestigationEvents.HandoffRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// P3 저장이 커밋된 뒤에만 다른 담당(P4 보고서 · P5 금융 조치)을 부른다 (02-code 4절)
// 실패하면 EVENT_PUBLICATION에 미완료로 남는다. 자동 재발행은 이벤트 공통 설정 작업에서 정한다
@Slf4j
@Component
@RequiredArgsConstructor
class InvestigationAfterCommitListener {

    private final ReportGateway reportGateway;
    private final FinancialActionGateway financialActionGateway;
    private final CaseStatusService caseStatusService;
    private final HandoffService handoffService;

    @TransactionalEventListener
    void on(CaseEvidenceChanged event) {
        reportGateway.request(event.caseId(), event.evidenceVersion());
        caseStatusService.refreshById(event.caseId());
    }

    // P5 호출은 P3 트랜잭션 밖에서 한다 — P5 접수와 P3 반영의 트랜잭션을 섞지 않는다
    @TransactionalEventListener
    void on(HandoffRequested event) {
        handoffService.commandFor(event.handoffId()).ifPresent(command -> {
            Receipt receipt;
            try {
                receipt = financialActionGateway.request(command);
            } catch (RuntimeException e) {
                // 응답 유실 · 오류만으로 실패라고 단정하지 않는다 (fds 정본 03 §3.8)
                log.warn("금융 조치 요청 응답 없음 handoffId={}", event.handoffId(), e);
                receipt = Receipt.unknown();
            }
            handoffService.applyReceipt(command.idempotencyKey(), receipt);
        });
    }
}
