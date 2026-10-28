package com.donworry.payment.service;

import static com.donworry.payment.service.PaymentFdsReevaluationNotAllowedException.Reason.*;

import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentFdsReevaluationAdmissionService {
    private final PaymentFdsStaffAccessService access;
    private final PaymentRepository payments;
    private final PaymentRequestRepository requests;
    private final PaymentFdsRecoveryRepository recoveries;

    // 실행 트랜잭션과 분리해 REQUESTED와 최초 작업 키를 먼저 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentFdsReevaluationAdmission admit(Long paymentId) {
        long staffId = access.requireStaff();
        if (paymentId == null || paymentId <= 0) throw new IllegalArgumentException("결제 ID는 양수여야 합니다.");

        var payment = payments.findByIdForReevaluation(paymentId).orElseThrow(PaymentOperationsException::new);
        var request = requests.findByPaymentIdForReevaluation(paymentId)
                .orElseThrow(() -> new PaymentFdsReevaluationNotAllowedException(RECOVERY_DATA_MISSING));
        var recovery = recoveries
                .findByPaymentIdForReevaluation(paymentId)
                .orElseThrow(() -> new PaymentFdsReevaluationNotAllowedException(RECOVERY_DATA_MISSING));

        // 기존 작업을 먼저 반환하며 실행 직전의 현재 상태 검사는 후속 실행 서비스가 맡는다.
        if (recovery.getStatus() == PaymentFdsRecoveryStatus.REQUESTED
                || recovery.getStatus() == PaymentFdsRecoveryStatus.COMPLETED)
            return PaymentFdsReevaluationAdmission.from(recovery);
        if (recovery.getStatus() == PaymentFdsRecoveryStatus.MANUAL_REVIEW) {
            if (recovery.getNewEvaluationRequestId() != null
                    && recovery.getNewEvaluationRequestId().equals(request.getEvaluationRequestId())
                    && "ERROR".equals(request.getEvaluationResult()))
                return PaymentFdsReevaluationAdmission.from(recovery);
            throw new PaymentFdsReevaluationNotAllowedException(
                    recovery.getRetryId() == null ? ANALYSIS_NOT_ALLOWED : PAYMENT_STATE_CHANGED);
        }
        if (recovery.getStatus() == PaymentFdsRecoveryStatus.WAITING_ANALYSIS)
            throw new PaymentFdsReevaluationNotAllowedException(ANALYSIS_PENDING);
        if (payment.getStatus() != PaymentStatus.PENDING
                || payment.getAuthorizationRef() != null
                || request.getProcessingStage() != PaymentProcessingStage.NEEDS_REVIEW
                || !recovery.matchesOriginal(request.evaluationSnapshot().orElse(null)))
            throw new PaymentFdsReevaluationNotAllowedException(PAYMENT_STATE_CHANGED);

        recovery.requestReevaluation(UUID.randomUUID(), UUID.randomUUID(), staffId, Instant.now());
        return PaymentFdsReevaluationAdmission.from(recovery);
    }
}
