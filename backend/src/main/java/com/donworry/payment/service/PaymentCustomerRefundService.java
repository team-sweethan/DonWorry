package com.donworry.payment.service;

import com.donworry.payment.entity.*;
import com.donworry.payment.repository.PaymentCancellationRepository;
import com.donworry.settlement.api.*;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentCustomerRefundService {
    private final PaymentRefunder refunder;
    private final PaymentCancellationRepository cancellations;
    private final ObjectProvider<PaymentRefundCompletion> completion;

    public void requireReady() {
        publisher();
    }

    // 접수 서비스가 결제 잠금과 원 요청/직원 매입 근거를 대조한 동일 트랜잭션에서만 실행한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(PaymentCancellationRequest request, Payment payment, CaptureSnapshot capture) {
        var publisher = publisher();
        if (payment.getStatus() != PaymentStatus.CAPTURED
                || capture == null
                || !payment.getPaymentId().equals(capture.paymentId())
                || capture.captureId() == null
                || capture.captureId() <= 0
                || !"COMPLETED".equals(capture.captureStatus())
                || capture.capturedAt() == null
                || !payment.getAmount().equals(capture.captureAmount())
                || cancellations.findByPaymentId(payment.getPaymentId()).isPresent())
            throw new IllegalStateException("신규 전액 환불 근거가 일치하지 않습니다");
        var work = cancellations.save(PaymentCancellation.start(
                request, payment, PaymentCancellationOperation.REFUND, capture.captureId(), Instant.now()));
        RefundResult result;
        try {
            result = refunder.refund(new RefundCommand(payment.getPaymentId(), work.getCaptureId(), work.getAmount()));
        } catch (RefundUnavailableException e) {
            throw unavailable();
        }
        if (result == null) throw unavailable();
        if (result == RefundResult.DECLINED) {
            work.fail("REFUND_DECLINED", Instant.now());
            return;
        }
        // 매입 사실과 원 시각은 보존하고, 환불 성공은 전용 작업에 기록한다.
        work.succeed(Instant.now());
        publisher.publish(new PaymentRefundSucceeded(
                work.getCancellationId(),
                payment.getPaymentId(),
                work.getCaptureId(),
                payment.getMerchantId(),
                work.getAmount(),
                work.getCompletedAt()));
    }

    private PaymentRefundCompletion publisher() {
        var publisher = completion.getIfAvailable();
        if (publisher == null) throw new PaymentHttpException(503, "CANCELLATION_NOT_READY", "환불 실행 연결이 준비되지 않았습니다.");
        return publisher;
    }

    private PaymentHttpException unavailable() {
        return new PaymentHttpException(503, "CANCELLATION_UNAVAILABLE", "처리 결과를 확인할 수 없습니다. 같은 요청 키로 확인해주세요.");
    }
}
