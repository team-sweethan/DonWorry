package com.donworry.payment.service;

import static com.donworry.payment.service.PaymentFdsReevaluationNotAllowedException.Reason.*;

import com.donworry.fds.api.*;
import com.donworry.payment.dto.PaymentFdsRecoveryResponse;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentFdsReevaluationExecutionService {
    private final PaymentFdsStaffAccessService access;
    private final PaymentRepository payments;
    private final PaymentRequestRepository requests;
    private final PaymentFdsRecoveryRepository recoveries;
    private final FdsReevaluationRequester reevaluation;
    private final PaymentAuthorizationService authorization;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeoutString = "${payment.fds-reevaluation.execution-timeout-seconds:15}")
    public PaymentFdsReevaluationExecution execute(Long paymentId, UUID retryId) {
        access.requireStaff();
        if (paymentId == null || paymentId <= 0 || retryId == null)
            throw new IllegalArgumentException("접수한 재평가 작업이 필요합니다.");
        var payment = payments.findByIdForReevaluation(paymentId).orElseThrow(PaymentOperationsException::new);
        var request = requests.findByPaymentIdForReevaluation(paymentId)
                .orElseThrow(() -> new PaymentFdsReevaluationNotAllowedException(RECOVERY_DATA_MISSING));
        var recovery = recoveries
                .findByPaymentIdForReevaluation(paymentId)
                .orElseThrow(() -> new PaymentFdsReevaluationNotAllowedException(RECOVERY_DATA_MISSING));
        if (!retryId.equals(recovery.getRetryId())) throw new IllegalStateException("접수한 재평가 작업 키가 일치하지 않습니다.");

        if (recovery.getStatus() == PaymentFdsRecoveryStatus.COMPLETED) {
            if (!recovery.getNewEvaluationRequestId().equals(request.getEvaluationRequestId())
                    || request.getProcessingStage() != PaymentProcessingStage.AUTHORIZED
                    || "ERROR".equals(request.getEvaluationResult()))
                throw new IllegalStateException("완료된 재평가와 현재 평가가 일치하지 않습니다.");
            return result(recovery, payment, request, null);
        }
        if (recovery.getStatus() == PaymentFdsRecoveryStatus.MANUAL_REVIEW) {
            boolean appliedError = recovery.getNewEvaluationRequestId().equals(request.getEvaluationRequestId())
                    && "ERROR".equals(request.getEvaluationResult())
                    && request.getProcessingStage() == PaymentProcessingStage.NEEDS_REVIEW;
            return result(recovery, payment, request, appliedError ? null : PAYMENT_STATE_CHANGED);
        }
        if (recovery.getStatus() != PaymentFdsRecoveryStatus.REQUESTED)
            throw new IllegalStateException("접수되지 않은 재평가입니다.");
        if (payment.getStatus() != PaymentStatus.PENDING
                || payment.getAuthorizationRef() != null
                || request.getProcessingStage() != PaymentProcessingStage.NEEDS_REVIEW
                || !recovery.matchesOriginal(request.evaluationSnapshot().orElse(null))) {
            // 예외로 종료 기록을 롤백하지 않는다. 흐름 서비스가 커밋 후 409 사유를 전달한다.
            recovery.stopReevaluation(Instant.now());
            return result(recovery, payment, request, PAYMENT_STATE_CHANGED);
        }

        // 재평가도 최초 평가와 같은 입력이어야 하므로 시도별로 저장한 FDS 식별자를 그대로 보낸다 (#325).
        var signals = request.riskSignals();
        var response = reevaluation.reevaluate(FdsReevaluationRequest.builder()
                .originalEvaluationRequestId(recovery.getOriginalEvaluationRequestId())
                .newEvaluationRequestId(recovery.getNewEvaluationRequestId())
                .paymentId(paymentId)
                .merchantId(payment.getMerchantId())
                .customerRef(payment.getCustomerRef())
                .amount(payment.getAmount())
                .paymentRequestedAt(payment.getCreatedAt())
                .deviceId(signals.deviceId())
                .cardToken(signals.cardToken())
                .cardDisplayLabel(signals.cardDisplayLabel())
                .ipCountryCode(signals.ipCountryCode())
                .ipAddress(signals.ipAddress())
                .buyerEmail(signals.buyerEmail())
                .retryId(retryId)
                .build());
        var snapshot = validatedSnapshot(response, recovery);
        request.applyReevaluation(recovery, snapshot);
        if (response.result() != FdsResult.ERROR) {
            var decision = PaymentRules.afterFds(
                    PaymentRules.State.pending(), PaymentRules.FdsVerdict.valueOf(snapshot.result()));
            authorization.authorizeInTransaction(new PaymentAuthorizationContext(
                    PaymentCreationSnapshot.from(payment),
                    snapshot.requestId(),
                    new PaymentEvaluationResult(response, decision)));
            request.completeAuthorization();
        }
        recovery.finishReevaluation(request, Instant.now());
        return result(recovery, payment, request, null);
    }

    private PaymentEvaluationSnapshot validatedSnapshot(FdsEvaluationResponse response, PaymentFdsRecovery recovery) {
        if (response == null
                || response.result() == null
                || !recovery.getNewEvaluationRequestId().equals(response.evaluationRequestId())
                || recovery.getOriginalEvaluationId().equals(response.evaluationId()))
            throw new IllegalStateException("P2 재평가 응답이 작업과 일치하지 않습니다.");
        try {
            return new PaymentEvaluationSnapshot(
                    response.evaluationRequestId(),
                    response.evaluationId(),
                    response.result().name(),
                    response.riskScore(),
                    response.errorCode(),
                    response.evaluatedAt());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("P2 재평가 응답 필드가 유효하지 않습니다.");
        }
    }

    private PaymentFdsReevaluationExecution result(
            PaymentFdsRecovery recovery,
            Payment payment,
            PaymentRequest request,
            PaymentFdsReevaluationNotAllowedException.Reason rejection) {
        return new PaymentFdsReevaluationExecution(
                PaymentFdsRecoveryResponse.from(recovery, payment, request), rejection);
    }
}
