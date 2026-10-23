package com.donworry.payment.service;

import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.CaptureCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCaptureService {
    private final PaymentRepository payments;

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
        payment.completeCapture();
    }
}
