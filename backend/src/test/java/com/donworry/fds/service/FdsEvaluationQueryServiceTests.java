package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleHitOutcome;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class FdsEvaluationQueryServiceTests {

    @Mock
    private FdsEvaluationRepository evaluationRepository;

    // 판정을 지정하면 해당 평가 이력만 반환한다.
    @Test
    void list_resultFilter_returnsMatchingEvaluationPage() {
        var review = evaluation(101L, FdsResult.REVIEW);
        var queryService = new FdsEvaluationQueryService(evaluationRepository);
        given(evaluationRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<FdsEvaluation>>any(),
                        org.mockito.ArgumentMatchers.any(Pageable.class)))
                .willReturn(new PageImpl<>(java.util.List.of(review), PageRequest.of(0, 10), 1));
        given(evaluationRepository.countByResult(org.mockito.ArgumentMatchers.any()))
                .willReturn(0L);
        given(evaluationRepository.countByResult(FdsResult.REVIEW)).willReturn(1L);

        var result = queryService.list(FdsResult.REVIEW, false, null, 0, 10);

        assertThat(result.content()).singleElement().satisfies(summary -> {
            assertThat(summary.paymentId()).isEqualTo(101L);
            assertThat(summary.result()).isEqualTo(FdsResult.REVIEW);
        });
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.resultCounts()).containsEntry(FdsResult.REVIEW, 1L);
    }

    // 상세 조회는 룰 근거와 동일 결제 이력을 함께 반환한다.
    @Test
    void detail_existingEvaluation_returnsRuleHitsAndHistory() {
        var evaluation = evaluation(101L, FdsResult.REVIEW);
        evaluation.addRuleHit(
                "HIGH_AMOUNT",
                FdsRuleHitOutcome.MATCH,
                35,
                "고액 결제",
                "{\"amountGte\":150000,\"score\":35}",
                "{\"amount\":150000}",
                null,
                null);
        var queryService = new FdsEvaluationQueryService(evaluationRepository);
        given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
        given(evaluationRepository.findByPaymentIdOrderByEvaluatedAtDesc(101L)).willReturn(List.of(evaluation));

        var result = queryService.detail(1L);

        assertThat(result.evaluation().paymentId()).isEqualTo(101L);
        assertThat(result.ruleHits()).singleElement().satisfies(hit -> {
            assertThat(hit.ruleCode()).isEqualTo("HIGH_AMOUNT");
            assertThat(hit.appliedScore()).isEqualTo(35);
            assertThat(hit.observedValue()).isEqualTo("{\"amount\":150000}");
            assertThat(hit.thresholdSnapshot()).isEqualTo("{\"amountGte\":150000,\"score\":35}");
        });
        assertThat(result.history()).hasSize(1);
    }

    // 없는 평가 ID는 조회 실패 예외를 반환한다.
    @Test
    void detail_missingEvaluation_throwsNotFoundException() {
        var queryService = new FdsEvaluationQueryService(evaluationRepository);
        given(evaluationRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.detail(999L)).isInstanceOf(FdsEvaluationNotFoundException.class);
    }

    // 카탈로그는 구현된 R01~R08과 최근 적중 빈도를 함께 반환한다.
    @Test
    void ruleCatalog_returnsImplementedRulesWithRecentFrequency() {
        var evaluation = evaluation(101L, FdsResult.DENY);
        evaluation.addRuleHit(
                "R08",
                FdsRuleHitOutcome.MATCH,
                35,
                "거절 후 재시도",
                "{\"windowMinutes\":10,\"declinedCountGte\":2,\"score\":35}",
                "{\"declinedCount\":2}",
                null,
                null);
        given(evaluationRepository.findByEvaluatedAtGreaterThanEqual(org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(evaluation));
        var queryService = new FdsEvaluationQueryService(evaluationRepository);

        var result = queryService.ruleCatalog();

        assertThat(result)
                .extracting(entry -> entry.ruleCode())
                .containsExactly("R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08");
        assertThat(result.getLast()).satisfies(entry -> {
            assertThat(entry.recentMatchCount()).isEqualTo(1);
            assertThat(entry.lastMatchedAt()).isEqualTo(evaluation.getEvaluatedAt());
            assertThat(entry.dailyMatchCounts()).hasSize(7);
        });
    }

    private FdsEvaluation evaluation(Long paymentId, FdsResult result) {
        var requestedAt = Instant.now();
        var request = new FdsEvaluationRequest(UUID.randomUUID(), paymentId, 1L, "hong-gildong", 150_000L, requestedAt);
        return FdsEvaluation.completed(
                request, result, result == FdsResult.REVIEW ? 60 : 0, requestedAt.plusSeconds(1));
    }
}
