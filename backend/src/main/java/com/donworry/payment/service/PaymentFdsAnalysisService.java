package com.donworry.payment.service;

import com.donworry.fds.api.FdsEvaluationFailureAnalyzed;
import com.donworry.payment.repository.PaymentFdsRecoveryRepository;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.payment.repository.PaymentRequestRepository;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentFdsAnalysisService {
    private final PaymentRepository payments;
    private final PaymentRequestRepository requests;
    private final PaymentFdsRecoveryRepository recoveries;

    @Transactional
    public void receive(FdsEvaluationFailureAnalyzed event) {
        Objects.requireNonNull(event, "event");
        if (event.paymentId() == null || event.paymentId() <= 0) throw new IllegalArgumentException("결제 ID는 양수여야 합니다");
        // 재평가 접수·실행과 같은 순서로 잠그고, 대기 후 최신 복구 행을 대조한다.
        payments.findByIdForUpdate(event.paymentId()).orElseThrow(() -> new IllegalStateException("분석 대상 결제가 없습니다"));
        requests.findByPaymentIdForUpdate(event.paymentId())
                .orElseThrow(() -> new IllegalStateException("분석 대상 결제 요청이 없습니다"));
        var recovery = recoveries
                .findByPaymentIdForUpdate(event.paymentId())
                .orElseThrow(() -> new IllegalStateException("분석 대상 복구 기록이 없습니다"));
        recovery.recordAnalysis(event, Instant.now());
    }
}
