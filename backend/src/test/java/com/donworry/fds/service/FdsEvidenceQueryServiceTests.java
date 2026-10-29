package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleHitOutcome;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FdsEvidenceQueryServiceTests {

    @Mock
    private FdsEvaluationRepository evaluationRepository;

    // 최신 ERROR가 있어도 마지막 정상 평가는 Case 화면의 최종 유효 판정으로 반환한다.
    @Test
    void findLatestByPaymentIds_whenLatestEvaluationIsError_returnsLatestNonErrorWithMatchedRules() {
        var review = evaluation(100L, FdsResult.REVIEW, Instant.parse("2026-10-26T01:00:00Z"));
        review.addRuleHit("HIGH_AMOUNT", FdsRuleHitOutcome.MATCH, 35, "고액 결제");
        review.addRuleHit("RAPID_REPEAT_PAYMENT", FdsRuleHitOutcome.NO_MATCH, 0, "단시간 반복 결제");
        var error = evaluation(100L, FdsResult.ERROR, Instant.parse("2026-10-26T01:01:00Z"));
        var queryService = new FdsEvidenceQueryService(evaluationRepository);
        given(evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(anyCollection()))
                .willReturn(List.of(error, review));

        var result = queryService.findLatestByPaymentIds(List.of(100L));

        assertThat(result).singleElement().satisfies(evidence -> {
            assertThat(evidence.paymentId()).isEqualTo(100L);
            assertThat(evidence.result()).isEqualTo(FdsResult.REVIEW);
            assertThat(evidence.matchedRules()).singleElement().satisfies(rule -> {
                assertThat(rule.ruleCode()).isEqualTo("HIGH_AMOUNT");
                assertThat(rule.ruleName()).isEqualTo("고액 결제");
                assertThat(rule.appliedScore()).isEqualTo(35);
            });
        });
    }

    // 적중 룰에는 평가 당시 저장한 기준값 · 실제값 JSON 원본이 그대로 실린다 (#326)
    @Test
    void findLatestByPaymentIds_matchedRule_includesThresholdAndObserved() {
        var review = evaluation(102L, FdsResult.REVIEW, Instant.parse("2026-10-26T01:00:00Z"));
        review.addRuleHit(
                "R01",
                FdsRuleHitOutcome.MATCH,
                30,
                "고액 결제",
                "{\"amountGte\":500000,\"score\":30}",
                "{\"amount\":600000}",
                null,
                null);
        var queryService = new FdsEvidenceQueryService(evaluationRepository);
        given(evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(anyCollection()))
                .willReturn(List.of(review));

        var result = queryService.findLatestByPaymentIds(List.of(102L));

        assertThat(result)
                .singleElement()
                .satisfies(evidence -> assertThat(evidence.matchedRules())
                        .singleElement()
                        .satisfies(rule -> {
                            assertThat(rule.thresholdSnapshot()).isEqualTo("{\"amountGte\":500000,\"score\":30}");
                            assertThat(rule.observedValue()).isEqualTo("{\"amount\":600000}");
                        }));
    }

    // 정상 판정이 전혀 없으면 최신 ERROR를 반환해 평가 이력 없음과 구분한다.
    @Test
    void findLatestByPaymentIds_whenOnlyErrors_returnsLatestError() {
        var olderError = evaluation(101L, FdsResult.ERROR, Instant.parse("2026-10-26T01:00:00Z"));
        var latestError = evaluation(101L, FdsResult.ERROR, Instant.parse("2026-10-26T01:01:00Z"));
        var queryService = new FdsEvidenceQueryService(evaluationRepository);
        given(evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(anyCollection()))
                .willReturn(List.of(latestError, olderError));

        var result = queryService.findLatestByPaymentIds(List.of(101L));

        assertThat(result).singleElement().satisfies(evidence -> {
            assertThat(evidence.result()).isEqualTo(FdsResult.ERROR);
            assertThat(evidence.matchedRules()).isEmpty();
        });
    }

    // 없는 결제는 결과에서 빼 P3가 Case 화면에서 평가 없음으로 표시한다.
    @Test
    void findLatestByPaymentIds_whenEvaluationIsMissing_omitsPayment() {
        var queryService = new FdsEvidenceQueryService(evaluationRepository);
        given(evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(anyCollection()))
                .willReturn(List.of(evaluation(101L, FdsResult.ALLOW, Instant.parse("2026-10-26T01:00:00Z"))));

        var result = queryService.findLatestByPaymentIds(List.of(100L, 101L));

        assertThat(result)
                .singleElement()
                .extracting(evidence -> evidence.paymentId())
                .isEqualTo(101L);
    }

    // 공개 조회 계약은 null·0 이하 결제 ID를 허용하지 않는다.
    @Test
    void findLatestByPaymentIds_whenPaymentIdsAreInvalid_throwsIllegalArgumentException() {
        var queryService = new FdsEvidenceQueryService(evaluationRepository);

        assertThatThrownBy(() -> queryService.findLatestByPaymentIds(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queryService.findLatestByPaymentIds(List.of(0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FdsEvaluation evaluation(Long paymentId, FdsResult result, Instant evaluatedAt) {
        var request = new FdsEvaluationRequest(UUID.randomUUID(), paymentId, 1L, "hong-gildong", 150_000L, evaluatedAt);
        return FdsEvaluation.completed(request, result, result == FdsResult.ERROR ? 0 : 60, evaluatedAt);
    }
}
