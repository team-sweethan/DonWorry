package com.donworry.investigation.service;

import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.service.FinancialActionGateway.Command;
import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// "인계 상태 다시 확인" — 같은 멱등키로 P5에 저장된 결과를 받아 반영한다 (#158 · #161)
// P6 매입을 다시 실행하지 않고 새 키도 만들지 않는다. 시간이 지났다고 실패 · 종료로 바꾸지도 않는다
// 트랜잭션을 걸지 않는다 — P5 호출을 P3 트랜잭션 밖에서 하려고
@Slf4j
@Service
@RequiredArgsConstructor
public class HandoffRecheckService {

    private final SecurityStaffGuard securityStaffGuard;
    private final HandoffService handoffService;
    private final FinancialActionGateway financialActionGateway;
    private final CaseAccessGuard caseAccessGuard;

    // 열린 사건은 담당자만 (#612). 판단을 P5 호출보다 먼저 한다
    public void recheck(Long caseId, Long handoffId) {
        Long staffId = securityStaffGuard.requireSecurityStaffId();
        caseAccessGuard.requireAccess(caseId, staffId, StaffAccessType.HANDOFF_RECHECK);
        Command command = handoffService.recheckCommandFor(caseId, handoffId);
        Receipt receipt;
        try {
            receipt = financialActionGateway.request(command);
        } catch (RuntimeException e) {
            log.warn("인계 상태 다시 확인 응답 없음 handoffId={}", handoffId, e);
            throw new InvestigationException(InvestigationErrorCode.PAYMENT_ACTION_UNAVAILABLE);
        }
        handoffService.applyRecheck(command.idempotencyKey(), receipt, staffId);
    }
}
