package com.donworry.investigation.listener;

import com.donworry.investigation.service.CaseIntakeService;
import com.donworry.investigation.service.HeldPayment;
import com.donworry.payment.api.PaymentHeld;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// P5 HELD 통지를 받아 Case를 만들거나 병합한다 (ADR-0023)
// P5가 롤백한 결제로 Case를 만들지 않도록 발행 트랜잭션의 커밋 후에 받는다
// 처리에 실패하면 예외가 그대로 나가 EVENT_PUBLICATION에 미완료로 남고, 재전달 때 같은 결제는 한 번만 연결된다
@Component
@RequiredArgsConstructor
public class PaymentHeldListener {

    private final CaseIntakeService caseIntakeService;

    // 병합 기준 시각은 heldAt이 아니라 수신 처리 시각이다 — 지연 · 재전달 때 과거 시점으로 병합을 재구성하지 않는다
    @ApplicationModuleListener
    public void on(PaymentHeld event) {
        caseIntakeService.receive(new HeldPayment(
                event.paymentId(),
                event.merchantId(),
                event.customerRef(),
                event.amount(),
                event.authorizationRef(),
                event.evaluationId(),
                event.heldAt()));
    }
}
