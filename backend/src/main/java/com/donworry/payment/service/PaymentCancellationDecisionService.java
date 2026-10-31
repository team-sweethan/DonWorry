package com.donworry.payment.service;

import com.donworry.payment.entity.*;
import com.donworry.settlement.api.CaptureSnapshot;
import org.springframework.stereotype.Service;

@Service
public class PaymentCancellationDecisionService {
    public PaymentCancellationResolution decide(
            Payment payment, PaymentRequest original, PaymentAction action, CaptureSnapshot capture) {
        boolean authorized = payment.getAuthorizationRef() != null
                && !payment.getAuthorizationRef().isBlank();
        if (original != null
                && (!payment.getPaymentId().equals(original.getPaymentId())
                        || !payment.getMerchantId().equals(original.getMerchantId()))) return review();
        if (action != null && !payment.getPaymentId().equals(action.getPaymentId())) return review();
        if (capture != null
                && (!payment.getPaymentId().equals(capture.paymentId())
                        || capture.captureId() == null
                        || capture.captureId() <= 0
                        || !payment.getAmount().equals(capture.captureAmount()))) return review();
        if (capture != null && payment.getStatus() != PaymentStatus.CAPTURED) return review();
        return switch (payment.getStatus()) {
            case PENDING -> {
                if (action != null || original == null) yield review();
                var stage = original.getProcessingStage();
                if (!authorized
                        && (stage == PaymentProcessingStage.RECEIVED || stage == PaymentProcessingStage.EVALUATED))
                    yield PaymentCancellationResolution.DEFERRED;
                if (authorized
                        && stage == PaymentProcessingStage.AUTHORIZED
                        && "ALLOW".equals(original.getEvaluationResult())) yield PaymentCancellationResolution.DEFERRED;
                yield review();
            }
            case HELD -> {
                if (!authorized || !authorizedOriginal(original, "REVIEW")) yield review();
                if (action != null) {
                    if (action.getActionType() == PaymentActionType.CAPTURE
                            && action.getStatus() == PaymentActionStatus.PENDING)
                        yield PaymentCancellationResolution.DEFERRED;
                    yield review();
                }
                yield PaymentCancellationResolution.ACCEPTED;
            }
            case CAPTURED -> {
                if (!authorized
                        || (original != null && original.getProcessingStage() != PaymentProcessingStage.AUTHORIZED)
                        || capture == null
                        || !"COMPLETED".equals(capture.captureStatus())
                        || capture.capturedAt() == null) yield review();
                if (original != null && !(action == null ? "ALLOW" : "REVIEW").equals(original.getEvaluationResult()))
                    yield review();
                if (action != null
                        && (action.getActionType() != PaymentActionType.CAPTURE
                                || action.getStatus() != PaymentActionStatus.SUCCEEDED
                                || !capture.captureId().equals(action.getCaptureId())
                                || !capture.capturedAt().equals(action.getCompletedAt()))) yield review();
                // 정산 상태는 판단하지 않는다. 금융 실행·필수 성공 수신부 연결은 #383이다.
                yield PaymentCancellationResolution.ACCEPTED;
            }
            case VOIDED -> {
                if (!authorized || !authorizedOriginal(original, "REVIEW")) yield review();
                if (action != null
                        && (action.getActionType() != PaymentActionType.VOID
                                || action.getStatus() != PaymentActionStatus.SUCCEEDED)) yield review();
                yield PaymentCancellationResolution.LEGACY_VOID;
            }
            case DENIED, DECLINED -> {
                if (authorized || action != null) yield review();
                if (original != null) {
                    if (original.getProcessingStage() != PaymentProcessingStage.AUTHORIZED) yield review();
                    String result = original.getEvaluationResult();
                    if (payment.getStatus() == PaymentStatus.DENIED
                            ? !"DENY".equals(result)
                            : !("ALLOW".equals(result) || "REVIEW".equals(result))) yield review();
                }
                yield PaymentCancellationResolution.REJECTED;
            }
            case EXPIRED -> review();
        };
    }

    private boolean authorizedOriginal(PaymentRequest original, String result) {
        return original == null
                || (original.getProcessingStage() == PaymentProcessingStage.AUTHORIZED
                        && result.equals(original.getEvaluationResult()));
    }

    private PaymentCancellationResolution review() {
        return PaymentCancellationResolution.REVIEW_REQUIRED;
    }
}
