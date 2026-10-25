package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentActionStatus;
import com.donworry.payment.entity.PaymentActionType;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentActionRepository;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.CaptureCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCaptureService {
    private final PaymentRepository payments;
    private final PaymentActionRepository actions;
    private final ApplicationEventPublisher events;

    // 리스너의 트랜잭션에 참여하여 상태 반영 실패가 수신 실패로 전달되게 한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(CaptureCompleted event) {
        if (event == null
                || event.paymentId() == null
                || event.paymentId() <= 0
                || event.captureId() == null
                || event.captureId() <= 0
                || event.capturedAt() == null) {
            throw new IllegalArgumentException("매입 완료 이벤트의 식별자와 완료 시각이 필요합니다");
        }
        var payment =
                payments.findByIdForUpdate(event.paymentId()).orElseThrow(() -> new IllegalStateException("결제가 없습니다"));
        var stored = actions.findByPaymentId(event.paymentId());
        if (stored.isEmpty()) {
            // 기존 ALLOW 경로에는 직원 판정이 없으므로 P3 결과를 발행하지 않는다.
            payment.completeCapture();
            return;
        }
        var action = stored.get();
        if (action.getActionType() != PaymentActionType.CAPTURE || action.getStatus() == PaymentActionStatus.FAILED)
            throw new IllegalStateException("유효한 직원 CAPTURE 조치가 없습니다");
        if (action.getStatus() == PaymentActionStatus.SUCCEEDED) {
            if (payment.getStatus() != PaymentStatus.CAPTURED)
                throw new IllegalStateException("조치 결과와 결제 상태가 일치하지 않습니다");
            // 승인 참조와 원래 결과까지 검사하고 같은 완료는 추가 발행 없이 끝낸다.
            payment.completeCapture();
            action.completeCapture(event.captureId(), event.capturedAt());
            return;
        }
        payment.completeHeldCapture();
        action.completeCapture(event.captureId(), event.capturedAt());
        events.publishEvent(PaymentActionMapping.result(action));
    }
}
