package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.fds.api.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentObservedEvaluationTests {
    // P2 평가 시각이 과거여도 실제 호출 전후 시각을 별도로 관측한다
    @Test
    void observed_whenErrorResponse_capturesActualCallBoundaries() {
        Instant[] during = new Instant[2];
        FdsEvaluator evaluator = request -> {
            during[0] = Instant.now();
            var response = new FdsEvaluationResponse(
                    request.evaluationRequestId(),
                    1L,
                    FdsResult.ERROR,
                    null,
                    Instant.parse("2020-01-01T00:00:00Z"),
                    "TRANSIENT_TEST");
            during[1] = Instant.now();
            return response;
        };
        var input = PaymentEvaluationInput.builder()
                .evaluationRequestId(UUID.randomUUID())
                .paymentId(1L)
                .merchantId(1L)
                .customerRef("customer")
                .amount(100L)
                .paymentRequestedAt(Instant.now())
                .riskSignals(PaymentEvaluationFixtures.riskSignals())
                .build();
        var observed = new PaymentEvaluationService(evaluator).evaluateObserved(input);
        assertThat(observed.requestedAt()).isBeforeOrEqualTo(during[0]);
        assertThat(observed.respondedAt()).isAfterOrEqualTo(during[1]);
        assertThat(observed.requestedAt()).isNotEqualTo(observed.evaluation().evaluatedAt());
    }
}
