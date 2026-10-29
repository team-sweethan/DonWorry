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
        return decide(state, input, evaluation);
    }

    private PaymentEvaluationResult decide(
            PaymentRules.State state, PaymentEvaluationInput input, FdsEvaluationResponse evaluation) {
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

    public PaymentObservedEvaluation evaluateObserved(PaymentEvaluationInput input) {
        var request = request(input);
        var requestedAt = java.time.Instant.now();
        var response = fdsEvaluator.evaluate(request);
        var respondedAt = java.time.Instant.now();
        decide(PaymentRules.State.pending(), input, response);
        return new PaymentObservedEvaluation(response, requestedAt, respondedAt);
    }

    public FdsEvaluationResponse evaluate(PaymentEvaluationInput input) {
        return fdsEvaluator.evaluate(request(input));
    }

    private FdsEvaluationRequest request(PaymentEvaluationInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        var signals = input.riskSignals();
        return FdsEvaluationRequest.builder()
                .evaluationRequestId(input.evaluationRequestId())
                .paymentId(input.paymentId())
                .merchantId(input.merchantId())
                .customerRef(input.customerRef())
                .amount(input.amount())
                .paymentRequestedAt(input.paymentRequestedAt())
                .deviceId(signals.deviceId())
                .cardToken(signals.cardToken())
                .cardDisplayLabel(signals.cardDisplayLabel())
                .ipCountryCode(signals.ipCountryCode())
                .ipAddress(signals.ipAddress())
                .buyerEmail(signals.buyerEmail())
                .build();
    }
}
