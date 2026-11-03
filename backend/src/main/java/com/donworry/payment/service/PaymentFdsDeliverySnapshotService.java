package com.donworry.payment.service;

import static com.donworry.payment.service.PaymentFdsDeliveryNotAllowedException.Reason.*;

import com.donworry.payment.dto.PaymentFdsRecoveryResponse;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentFdsDeliverySnapshotService {
    private final PaymentRepository payments;
    private final PaymentRequestRepository requests;
    private final PaymentFdsRecoveryRepository recoveries;

    // 보고·재전달 전에 반드시 커밋해 P5 리스너가 같은 행을 잠글 수 있게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 15)
    public PaymentFdsDeliverySnapshot read(Long paymentId) {
        var payment = payments.findByIdForReevaluation(paymentId).orElseThrow(PaymentOperationsException::new);
        var request = requests.findByPaymentIdForReevaluation(paymentId)
                .orElseThrow(() -> new PaymentFdsDeliveryNotAllowedException(RECOVERY_DATA_MISSING));
        var recovery = recoveries
                .findByPaymentIdForReevaluation(paymentId)
                .orElseThrow(() -> new PaymentFdsDeliveryNotAllowedException(RECOVERY_DATA_MISSING));
        var summary = PaymentFdsRecoveryResponse.from(recovery, payment, request);
        if (recovery.getStatus() != PaymentFdsRecoveryStatus.WAITING_ANALYSIS)
            return new PaymentFdsDeliverySnapshot(summary, null);
        if (!recovery.matchesOriginal(request.evaluationSnapshot().orElse(null))
                || request.getProcessingStage() != PaymentProcessingStage.NEEDS_REVIEW
                || payment.getStatus() != PaymentStatus.PENDING
                || payment.getAuthorizationRef() != null)
            throw new PaymentFdsDeliveryNotAllowedException(RECOVERY_DATA_MISSING);
        return new PaymentFdsDeliverySnapshot(summary, PaymentFdsFailureReports.from(payment, request, recovery));
    }
}
