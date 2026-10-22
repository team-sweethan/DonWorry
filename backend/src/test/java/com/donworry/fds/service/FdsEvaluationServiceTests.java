package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FdsEvaluationServiceTests {

    @Mock
    private FdsEvaluationRepository evaluationRepository;

    @Captor
    private ArgumentCaptor<FdsEvaluation> evaluationCaptor;

    private FdsEvaluationService evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new FdsEvaluationService(evaluationRepository);
    }

    @Test
    // 세 번째 단시간 결제와 고액 결제가 함께 적중하면 REVIEW를 반환한다.
    void evaluate_highAmountAndThirdPaymentWithinWindow_returnsReview() {
        var requestedAt = Instant.parse("2026-10-22T01:09:00Z");
        when(evaluationRepository.findByEvaluationRequestId(any())).thenReturn(Optional.empty());
        when(evaluationRepository.countByCustomerRefAndPaymentRequestedAtBetween(
                        eq("hong-gildong"), eq(requestedAt.minusSeconds(10 * 60)), eq(requestedAt)))
                .thenReturn(2L);
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        var result = evaluator.evaluate(request(3L, 150_000L, requestedAt));

        assertThat(result.result()).isEqualTo(FdsResult.REVIEW);
        assertThat(result.riskScore()).isEqualTo(60);
        assertThat(result.evaluationId()).isEqualTo(1L);
        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        assertThat(evaluationCaptor.getValue().getCustomerRef()).isEqualTo("hong-gildong");
        assertThat(evaluationCaptor.getValue().getPaymentRequestedAt()).isEqualTo(requestedAt);
        assertThat(evaluationCaptor.getValue().getRuleHits()).hasSize(2);
        assertThat(evaluationCaptor.getValue().getRuleHits().getFirst().getObservedValue())
                .isEqualTo("{\"amount\":150000}");
        assertThat(evaluationCaptor.getValue().getRuleHits().getFirst().getThresholdSnapshot())
                .isEqualTo("{\"amountGte\":150000,\"score\":35}");
        assertThat(evaluationCaptor.getValue().getRuleHits().get(1).getObservedValue())
                .isEqualTo("{\"attemptCount\":3}");
    }

    @Test
    // 같은 요청 ID로 재호출하면 기존 평가 결과를 반환한다.
    void evaluate_existingRequestId_returnsStoredEvaluation() {
        var request = request(1L, 150_000L, Instant.parse("2026-10-22T01:00:00Z"));
        var stored = FdsEvaluation.completed(request, FdsResult.REVIEW, 60, Instant.parse("2026-10-22T01:00:01Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(stored, "evaluationId", 1L);
        when(evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId()))
                .thenReturn(Optional.of(stored));

        var result = evaluator.evaluate(request);

        assertThat(result.evaluationId()).isEqualTo(1L);
        assertThat(result.result()).isEqualTo(FdsResult.REVIEW);
        verify(evaluationRepository).findByEvaluationRequestId(request.evaluationRequestId());
    }

    private FdsEvaluationRequest request(Long paymentId, long amount, Instant requestedAt) {
        return new FdsEvaluationRequest(UUID.randomUUID(), paymentId, "hong-gildong", amount, requestedAt);
    }
}
