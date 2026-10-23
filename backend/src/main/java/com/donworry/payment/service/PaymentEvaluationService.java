package com.donworry.payment.service;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

// P2 구현 Bean과 결제 흐름을 연결할 때 Spring Bean으로 등록한다.
@RequiredArgsConstructor
public class PaymentEvaluationService {

    @NonNull
    private final FdsEvaluator fdsEvaluator;

    public FdsEvaluationResponse evaluate(PaymentEvaluationInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        var request = FdsEvaluationRequest.builder()
                .evaluationRequestId(input.evaluationRequestId())
                .paymentId(input.paymentId())
                .customerRef(input.customerRef())
                .amount(input.amount())
                .paymentRequestedAt(input.paymentRequestedAt())
                .build();

        return fdsEvaluator.evaluate(request);
    }
}
