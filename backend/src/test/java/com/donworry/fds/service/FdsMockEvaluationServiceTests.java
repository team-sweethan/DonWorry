package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleHitOutcome;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FdsMockEvaluationServiceTests {

    @Mock
    private FdsEvaluator evaluator;

    @Mock
    private FdsEvaluationRepository evaluationRepository;

    @Captor
    private ArgumentCaptor<FdsEvaluationRequest> requestCaptor;

    // 버튼 요청은 공개 평가 계약으로 하드코딩된 Mock 요청 세 건을 보낸다.
    @Test
    void createMockEvaluations_sendsThreeMockP5Requests() {
        when(evaluator.evaluate(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, FdsEvaluationRequest.class);
            return new FdsEvaluationResponse(
                    request.evaluationRequestId(), 1L, FdsResult.ALLOW, 0, Instant.now(), null);
        });
        var service = new FdsMockEvaluationService(evaluator, evaluationRepository);

        var responses = service.createMockEvaluations();

        verify(evaluator, org.mockito.Mockito.times(3)).evaluate(requestCaptor.capture());
        var requests = requestCaptor.getAllValues();
        assertThat(responses).hasSize(3);
        assertThat(requests).extracting(FdsEvaluationRequest::merchantId).containsOnly(1L);
        assertThat(requests).extracting(FdsEvaluationRequest::customerRef).containsOnly("hong-gildong");
        assertThat(requests.get(2).amount()).isEqualTo(500_000L);
        assertThat(requests.get(2).paymentRequestedAt())
                .isAfter(requests.get(0).paymentRequestedAt())
                .isBeforeOrEqualTo(Instant.now());
    }

    // 기존 개발용 Mock 행만 비어 있던 룰 스냅샷을 채운다.
    @Test
    void createMockEvaluations_whenExistingMockHasNoSnapshots_backfillsOnlyThatMock() {
        var requestId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var requestedAt = Instant.parse("2026-10-22T01:00:00Z");
        var evaluation = FdsEvaluation.completed(
                new FdsEvaluationRequest(
                        requestId,
                        900_003L,
                        1L,
                        "hong-gildong",
                        500_000L,
                        requestedAt,
                        "device-mock-hong-gildong",
                        "card-token-mock-4242",
                        "VISA •••• 4242",
                        "KR",
                        "203.0.113.10",
                        "hong-gildong@example.test"),
                FdsResult.REVIEW,
                60,
                requestedAt.plusSeconds(1));
        evaluation.addRuleHit("R01", FdsRuleHitOutcome.MATCH, 30, "고액 결제");
        evaluation.addRuleHit("R04", FdsRuleHitOutcome.MATCH, 30, "단시간 반복 결제");
        when(evaluator.evaluate(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, FdsEvaluationRequest.class);
            return new FdsEvaluationResponse(
                    request.evaluationRequestId(), 1L, FdsResult.REVIEW, 60, Instant.now(), null);
        });
        when(evaluationRepository.findByEvaluationRequestId(any())).thenAnswer(invocation -> {
            UUID queriedRequestId = invocation.getArgument(0);
            return requestId.equals(queriedRequestId) ? Optional.of(evaluation) : Optional.empty();
        });
        var service = new FdsMockEvaluationService(evaluator, evaluationRepository);

        service.createMockEvaluations();

        assertThat(evaluation.getRuleHits().getFirst().getObservedValue()).isEqualTo("{\"amount\":500000}");
        assertThat(evaluation.getRuleHits().get(1).getObservedValue()).isEqualTo("{\"attemptCount\":3}");
        verify(evaluationRepository).save(evaluation);
        verify(evaluator, org.mockito.Mockito.times(3)).evaluate(any());
    }
}
