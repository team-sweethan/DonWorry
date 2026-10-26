package com.donworry.payment.service;

import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentRequestProcessingService {
    private final PaymentRepository payments;
    private final PaymentRequestRepository requests;
    private final PaymentAuthorizationService authorization;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PaymentEvaluationInput> evaluationInput(Long paymentId) {
        var request = requests.findByPaymentId(paymentId).orElseThrow();
        if (request.getProcessingStage() != PaymentProcessingStage.RECEIVED) return Optional.empty();
        var payment = payments.findById(paymentId).orElseThrow();
        return Optional.of(new PaymentEvaluationInput(
                request.getEvaluationRequestId(),
                paymentId,
                payment.getCustomerRef(),
                payment.getAmount(),
                payment.getCreatedAt()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveEvaluation(Long paymentId, FdsEvaluationResponse response) {
        payments.findByIdForUpdate(paymentId).orElseThrow();
        var request = requests.findByPaymentIdForUpdate(paymentId).orElseThrow();
        request.recordEvaluation(new PaymentEvaluationSnapshot(
                response.evaluationRequestId(),
                response.evaluationId(),
                response.result().name(),
                response.riskScore(),
                response.errorCode(),
                response.evaluatedAt()));
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
