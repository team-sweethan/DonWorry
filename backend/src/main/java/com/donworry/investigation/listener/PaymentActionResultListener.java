package com.donworry.investigation.listener;

import com.donworry.investigation.entity.ActionType;
import com.donworry.investigation.service.FinancialActionGateway.Command;
import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import com.donworry.investigation.service.HandoffService;
import com.donworry.payment.api.PaymentActionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// P5 금융 조치 최종 결과(실제 매입 완료 · 취소 완료 · 취소 거절)를 받아 인계와 Case에 반영한다 (ADR-0027)
// 같은 결과가 다시 오거나 응답 · 다시 확인보다 늦게 와도 한 번만 반영된다
// 인계와 맞지 않는 결과는 예외로 끝나 EVENT_PUBLICATION에 미완료로 남는다 — P5와 대조할 기록이다
@Component
@RequiredArgsConstructor
public class PaymentActionResultListener {

    private final HandoffService handoffService;

    @ApplicationModuleListener
    public void on(PaymentActionResult event) {
        handoffService.applyResult(
                new Command(
                        event.paymentId(),
                        event.decisionId(),
                        ActionType.valueOf(event.actionType()),
                        event.idempotencyKey()),
                Receipt.of(event.status(), event.targetOperationId(), event.errorCode()),
                event.completedAt());
    }
}
