package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationRequestConflictException;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.List;
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

    @Mock
    private FdsEvaluationTransactionService transactionService;

    @Captor
    private ArgumentCaptor<FdsEvaluation> evaluationCaptor;

    private FdsEvaluationTransactionService transactionEvaluator;

    private FdsEvaluationService evaluator;

    @BeforeEach
    void setUp() {
        transactionEvaluator = new FdsEvaluationTransactionService(evaluationRepository);
        evaluator = new FdsEvaluationService(evaluationRepository, transactionService);
    }

    @Test
    // 세 번째 단시간 결제와 고액 결제가 함께 적중하면 REVIEW를 반환한다.
    void evaluate_highAmountAndThirdPaymentWithinWindow_returnsReview() {
        var requestedAt = Instant.parse("2026-10-22T01:09:00Z");
        when(evaluationRepository.findByEvaluationRequestId(any())).thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        eq(1L), eq("hong-gildong"), eq(requestedAt.minusSeconds(10 * 60)), eq(requestedAt)))
                .thenReturn(List.of(
                        priorEvaluation(1L, requestedAt.minusSeconds(5 * 60)),
                        priorEvaluation(2L, requestedAt.minusSeconds(2 * 60))));
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        var result = transactionEvaluator.evaluate(request(3L, 150_000L, requestedAt));

        assertThat(result.result()).isEqualTo(FdsResult.REVIEW);
        assertThat(result.riskScore()).isEqualTo(60);
        assertThat(result.evaluationId()).isEqualTo(1L);
        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        assertThat(evaluationCaptor.getValue().getCustomerRef()).isEqualTo("hong-gildong");
        assertThat(evaluationCaptor.getValue().getMerchantId()).isEqualTo(1L);
        assertThat(evaluationCaptor.getValue().getPaymentRequestedAt()).isEqualTo(requestedAt);
        assertThat(evaluationCaptor.getValue().getRequestedAmount()).isEqualTo(150_000L);
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

        var result = transactionEvaluator.evaluate(request);

        assertThat(result.evaluationId()).isEqualTo(1L);
        assertThat(result.result()).isEqualTo(FdsResult.REVIEW);
        verify(evaluationRepository).findByEvaluationRequestId(request.evaluationRequestId());
    }

    // 같은 요청 ID가 다른 결제 입력에 재사용되면 기존 평가를 반환하지 않는다.
    @Test
    void evaluate_existingRequestIdWithDifferentInput_throwsRequestConflict() {
        var requestId = UUID.randomUUID();
        var original = request(requestId, 1L, 150_000L, Instant.parse("2026-10-22T01:00:00Z"));
        var stored = FdsEvaluation.completed(original, FdsResult.REVIEW, 60, Instant.parse("2026-10-22T01:00:01Z"));
        when(evaluationRepository.findByEvaluationRequestId(requestId)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> transactionEvaluator.evaluate(
                        request(requestId, 2L, 150_000L, Instant.parse("2026-10-22T01:00:00Z"))))
                .isInstanceOf(FdsEvaluationRequestConflictException.class);
    }

    @Test
    // 재시도는 결제·가맹점·고객·금액·요청 시각이 모두 원래 요청과 같아야 한다.
    void evaluation_sameRequestSnapshot_requiresAllRequestFieldsToMatch() {
        var requestId = UUID.randomUUID();
        var requestedAt = Instant.parse("2026-10-22T01:00:00.123456789Z");
        var original = request(requestId, 1L, "hong-gildong", 150_000L, requestedAt);
        var stored = FdsEvaluation.completed(original, FdsResult.REVIEW, 60, Instant.parse("2026-10-22T01:00:01Z"));

        assertThat(stored.hasSameRequestSnapshot(request(requestId, 1L, "hong-gildong", 150_000L, requestedAt)))
                .isTrue();
        assertThat(stored.hasSameRequestSnapshot(request(requestId, 2L, "hong-gildong", 150_000L, requestedAt)))
                .isFalse();
        assertThat(stored.hasSameRequestSnapshot(request(requestId, 1L, 2L, "hong-gildong", 150_000L, requestedAt)))
                .isFalse();
        assertThat(stored.hasSameRequestSnapshot(request(requestId, 1L, "other-customer", 150_000L, requestedAt)))
                .isFalse();
        assertThat(stored.hasSameRequestSnapshot(request(requestId, 1L, "hong-gildong", 150_001L, requestedAt)))
                .isFalse();
        assertThat(stored.hasSameRequestSnapshot(
                        request(requestId, 1L, "hong-gildong", 150_000L, requestedAt.plusSeconds(1))))
                .isFalse();
    }

    @Test
    // 같은 결제를 재평가해도 반복 결제 횟수는 하나로 센다.
    void evaluate_repeatedPaymentId_countsDistinctPayments() {
        var requestedAt = Instant.parse("2026-10-22T01:09:00Z");
        var request = request(3L, 150_000L, requestedAt);
        when(evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId()))
                .thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        any(), any(), any(), any()))
                .thenReturn(List.of(
                        priorEvaluation(1L, requestedAt.minusSeconds(5 * 60)),
                        priorEvaluation(3L, requestedAt.minusSeconds(2 * 60)),
                        priorEvaluation(3L, requestedAt.minusSeconds(30))));
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        var result = transactionEvaluator.evaluate(request);

        assertThat(result.riskScore()).isEqualTo(35);
        assertThat(result.result()).isEqualTo(FdsResult.ALLOW);
    }

    @Test
    // 같은 요청 ID의 동시 저장 충돌 뒤에는 이미 저장된 평가를 반환한다.
    void evaluate_whenConcurrentInsertConflicts_returnsStoredEvaluation() {
        var request = request(1L, 150_000L, Instant.parse("2026-10-22T01:00:00Z"));
        var stored = FdsEvaluation.completed(request, FdsResult.REVIEW, 60, Instant.parse("2026-10-22T01:00:01Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(stored, "evaluationId", 1L);
        when(transactionService.evaluate(request))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate request"));
        when(evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId()))
                .thenReturn(Optional.of(stored));

        var result = evaluator.evaluate(request);

        assertThat(result.evaluationId()).isEqualTo(1L);
    }

    // 동시 저장 충돌 후에도 다른 요청 스냅샷이면 P5에 충돌 예외를 전달한다.
    @Test
    void evaluate_concurrentConflictWithDifferentInput_throwsRequestConflict() {
        var requestId = UUID.randomUUID();
        var request = request(requestId, 2L, 150_000L, Instant.parse("2026-10-22T01:00:00Z"));
        var original = request(requestId, 1L, 150_000L, Instant.parse("2026-10-22T01:00:00Z"));
        var stored = FdsEvaluation.completed(original, FdsResult.REVIEW, 60, Instant.parse("2026-10-22T01:00:01Z"));
        when(transactionService.evaluate(request))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate request"));
        when(evaluationRepository.findByEvaluationRequestId(requestId)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> evaluator.evaluate(request)).isInstanceOf(FdsEvaluationRequestConflictException.class);
    }

    private FdsEvaluation priorEvaluation(Long paymentId, Instant requestedAt) {
        return FdsEvaluation.completed(request(paymentId, 50_000L, requestedAt), FdsResult.ALLOW, 0, requestedAt);
    }

    private FdsEvaluationRequest request(Long paymentId, long amount, Instant requestedAt) {
        return request(UUID.randomUUID(), paymentId, amount, requestedAt);
    }

    private FdsEvaluationRequest request(UUID requestId, Long paymentId, long amount, Instant requestedAt) {
        return request(requestId, paymentId, "hong-gildong", amount, requestedAt);
    }

    private FdsEvaluationRequest request(
            UUID requestId, Long paymentId, String customerRef, long amount, Instant requestedAt) {
        return request(requestId, paymentId, 1L, customerRef, amount, requestedAt);
    }

    private FdsEvaluationRequest request(
            UUID requestId, Long paymentId, Long merchantId, String customerRef, long amount, Instant requestedAt) {
        return new FdsEvaluationRequest(requestId, paymentId, merchantId, customerRef, amount, requestedAt);
    }
}
