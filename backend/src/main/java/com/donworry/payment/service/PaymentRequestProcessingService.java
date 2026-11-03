package com.donworry.payment.service;

import com.donworry.fds.api.FdsEvaluationFailureReport;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentRequestProcessingService {
    private final PaymentRepository payments;
    private final PaymentRequestRepository requests;
    private final PaymentAuthorizationService authorization;
    private final PaymentFdsRecoveryRepository recoveries;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PaymentEvaluationInput> evaluationInput(Long paymentId) {
        var request = requests.findByPaymentId(paymentId).orElseThrow();
        if (request.getProcessingStage() != PaymentProcessingStage.RECEIVED) return Optional.empty();
        var payment = payments.findById(paymentId).orElseThrow();
        return Optional.of(PaymentEvaluationInput.builder()
                .evaluationRequestId(request.getEvaluationRequestId())
                .paymentId(paymentId)
                .merchantId(payment.getMerchantId())
                .customerRef(payment.getCustomerRef())
                .amount(payment.getAmount())
                .paymentRequestedAt(payment.getCreatedAt())
                .riskSignals(request.riskSignals())
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<FdsEvaluationFailureReport> saveEvaluation(Long paymentId, PaymentObservedEvaluation observed) {
        var response = observed.evaluation();
        var payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        var request = requests.findByPaymentIdForUpdate(paymentId).orElseThrow();
        boolean first = request.getProcessingStage() == PaymentProcessingStage.RECEIVED;
        request.recordEvaluation(new PaymentEvaluationSnapshot(
                response.evaluationRequestId(),
                response.evaluationId(),
                response.result().name(),
                response.riskScore(),
                response.errorCode(),
                response.evaluatedAt()));
        if (response.result() != FdsResult.ERROR || !first) return Optional.empty();
        payment.requireUnapprovedPending();
        var recovery = recoveries.save(PaymentFdsRecovery.waitingAnalysis(
                paymentId,
                request.evaluationSnapshot().orElseThrow(),
                UUID.randomUUID(),
                observed.requestedAt(),
                observed.respondedAt()));
        return Optional.of(PaymentFdsFailureReports.from(payment, request, recovery));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void authorize(Long paymentId) {
        var payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        var request = requests.findByPaymentIdForUpdate(paymentId).orElseThrow();
        if (request.getProcessingStage() == PaymentProcessingStage.AUTHORIZED
                || request.getProcessingStage() == PaymentProcessingStage.NEEDS_REVIEW) return;
        if (request.getProcessingStage() != PaymentProcessingStage.EVALUATED)
            throw new IllegalStateException("저장한 평가 결과가 없습니다");
        var snapshot = request.evaluationSnapshot().orElseThrow();
        var response = new FdsEvaluationResponse(
                snapshot.requestId(),
                snapshot.evaluationId(),
                FdsResult.valueOf(snapshot.result()),
                snapshot.riskScore(),
                snapshot.evaluatedAt(),
                snapshot.errorCode());
        var decision =
                PaymentRules.afterFds(PaymentRules.State.pending(), PaymentRules.FdsVerdict.valueOf(snapshot.result()));
        authorization.authorizeInTransaction(new PaymentAuthorizationContext(
                PaymentCreationSnapshot.from(payment),
                snapshot.requestId(),
                new PaymentEvaluationResult(response, decision)));
        request.completeAuthorization();
    }
}
