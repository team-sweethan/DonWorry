package com.donworry.payment.service;

import com.donworry.fds.api.*;
import com.donworry.payment.entity.*;

// 최초 전송과 전달 복구가 같은 저장값으로 보고를 만든다. 미수집 정보는 채우지 않는다.
final class PaymentFdsFailureReports {
    private PaymentFdsFailureReports() {}

    static FdsEvaluationFailureReport from(Payment payment, PaymentRequest request, PaymentFdsRecovery recovery) {
        return FdsEvaluationFailureReport.builder()
                .failureReportId(recovery.getFailureReportId())
                .originalEvaluationRequestId(recovery.getOriginalEvaluationRequestId())
                .evaluationRequestId(recovery.getOriginalEvaluationRequestId())
                .evaluationId(recovery.getOriginalEvaluationId())
                .paymentId(payment.getPaymentId())
                .merchantId(payment.getMerchantId())
                .customerRef(payment.getCustomerRef())
                .amount(payment.getAmount())
                .paymentRequestedAt(payment.getCreatedAt())
                .evaluationResult(FdsResult.ERROR)
                .errorCode(recovery.getOriginalErrorCode())
                .requestedAt(recovery.getEvaluationRequestedAt())
                .respondedAt(recovery.getEvaluationRespondedAt())
                .paymentStatus(payment.getStatus().name())
                .processingStage(request.getProcessingStage().name())
                .requestId(request.getRequestId())
                .idempotencyKey(request.getIdempotencyKey())
                .orderRef(payment.getOrderRef())
                .evaluationAttemptNo(1)
                .build();
    }
}
