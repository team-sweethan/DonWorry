package com.donworry.payment.service;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentEvaluationService {

    @NonNull
    private final FdsEvaluator fdsEvaluator;

    public PaymentEvaluationResult evaluateAndDecide(PaymentRules.State state, PaymentEvaluationInput input) {
        PaymentRules.requireUnapprovedPending(state);
        var evaluation = evaluate(input);
        if (evaluation == null) {
            throw new IllegalStateException("FDS 평가 응답이 없습니다");
        }
        if (!input.evaluationRequestId().equals(evaluation.evaluationRequestId())) {
            throw new IllegalStateException("FDS 평가 응답의 요청 ID가 일치하지 않습니다");
        }

        var verdict = switch (evaluation.result()) {
            case ALLOW -> PaymentRules.FdsVerdict.ALLOW;
            case REVIEW -> PaymentRules.FdsVerdict.REVIEW;
            case DENY -> PaymentRules.FdsVerdict.DENY;
            case ERROR -> PaymentRules.FdsVerdict.ERROR;
        };
        return new PaymentEvaluationResult(evaluation, PaymentRules.afterFds(state, verdict));
    }

    public FdsEvaluationResponse evaluate(PaymentEvaluationInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        var request = FdsEvaluationRequest.builder()
                .evaluationRequestId(input.evaluationRequestId())
                .paymentId(input.paymentId())
                .merchantId(input.merchantId())
                .customerRef(input.customerRef())
                .amount(input.amount())
                .paymentRequestedAt(input.paymentRequestedAt())
                .build();

        return fdsEvaluator.evaluate(request);
    }
}
