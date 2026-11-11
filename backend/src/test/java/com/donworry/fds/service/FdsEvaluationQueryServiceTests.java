package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsFailureDisposition;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleHitOutcome;
import com.donworry.fds.entity.FdsBlocklistEntry;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.entity.FdsFailureAnalysis;
import com.donworry.fds.repository.FdsBlocklistEntryRepository;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.fds.repository.FdsFailureAnalysisRepository;
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
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class FdsEvaluationQueryServiceTests {

    @Mock
    private FdsEvaluationRepository evaluationRepository;

    @Mock
    private FdsFailureAnalysisRepository failureAnalysisRepository;

    @Mock
    private FdsFailureAnalysis failureAnalysis;

    @Mock
    private FdsBlocklistEntryRepository blocklistEntries;

    // 판정을 지정하면 해당 평가 이력만 반환한다.
    @Test
    void list_resultFilter_returnsMatchingEvaluationPage() {
        var review = evaluation(101L, FdsResult.REVIEW);
        var queryService = queryService();
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
        var queryService = queryService();
        given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
        given(evaluationRepository.findByPaymentIdOrderByEvaluatedAtDesc(101L)).willReturn(List.of(evaluation));
        given(failureAnalysisRepository.findByEvaluationRequestId(evaluation.getEvaluationRequestId()))
                .willReturn(Optional.of(failureAnalysis));
        given(failureAnalysis.getDisposition()).willReturn(FdsFailureDisposition.REEVALUATION_AVAILABLE);
        given(failureAnalysis.getReasonCode()).willReturn("TRANSIENT_ERROR_RECOVERED");
        given(failureAnalysis.getAnalyzedAt()).willReturn(Instant.parse("2026-11-02T01:00:00Z"));

        var result = queryService.detail(1L);

        assertThat(result.evaluation().paymentId()).isEqualTo(101L);
        assertThat(result.ruleHits()).singleElement().satisfies(hit -> {
            assertThat(hit.ruleCode()).isEqualTo("HIGH_AMOUNT");
            assertThat(hit.appliedScore()).isEqualTo(35);
            assertThat(hit.observedValue()).isEqualTo("{\"amount\":150000}");
            assertThat(hit.thresholdSnapshot()).isEqualTo("{\"amountGte\":150000,\"score\":35}");
        });
        assertThat(result.history()).hasSize(1);
        assertThat(result.blocklistCandidates())
                .extracting(candidate -> candidate.identifierType())
                .containsExactly("CARD_TOKEN", "BUYER_REF", "DEVICE_ID", "IP_ADDRESS", "BUYER_EMAIL");
        assertThat(result.failureAnalysis()).satisfies(analysis -> {
            assertThat(analysis.disposition()).isEqualTo(FdsFailureDisposition.REEVALUATION_AVAILABLE);
            assertThat(analysis.reasonCode()).isEqualTo("TRANSIENT_ERROR_RECOVERED");
        });
    }

    // 경보 상세 응답에는 이메일 원문이 없다 — 차단 후보와 R07 근거(#603 전 원문 기록 포함) 모두 가린 값 (ISMS-P 2.6.3)
    @Test
    void detail_withBuyerEmail_masksCandidateAndR07Evidence() {
        var evaluation = evaluation(101L, FdsResult.DENY);
        evaluation.addRuleHit(
                "R07",
                FdsRuleHitOutcome.MATCH,
                100,
                "차단 목록",
                "{\"active\":true}",
                "{\"buyerRef\":\"hong-gildong\",\"buyerEmail\":\"hong@example.test\","
                        + "\"matchedIdentifierTypes\":[\"BUYER_EMAIL\"]}",
                null,
                null);
        var queryService = queryService();
        given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
        given(evaluationRepository.findByPaymentIdOrderByEvaluatedAtDesc(101L)).willReturn(List.of(evaluation));

        var result = queryService.detail(1L);

        assertThat(result.blocklistCandidates())
                .filteredOn(candidate -> candidate.identifierType().equals("BUYER_EMAIL"))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.displayValue()).isEqualTo("ho***@example.test"));
        assertThat(result.ruleHits())
                .singleElement()
                .satisfies(hit -> assertThat(hit.observedValue())
                        .contains("\"maskedBuyerEmail\":\"ho***@example.test\"")
                        .doesNotContain("hong@example.test"));
    }

    // 차단 여부는 서버가 원문으로 대조해 알려 준다. 화면이 원문으로 /status를 부르지 않게 하는 계약 (#603)
    @Test
    void detail_whenCandidateBlocked_marksBlocked() {
        var evaluation = evaluation(101L, FdsResult.DENY);
        var queryService = queryService();
        given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
        given(evaluationRepository.findByPaymentIdOrderByEvaluatedAtDesc(101L)).willReturn(List.of(evaluation));
        given(blocklistEntries.findByIdentifierTypeAndIdentifierValue(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(List.of());
        org.mockito.BDDMockito.willReturn(
                        List.of(FdsBlocklistEntry.active("BUYER_EMAIL", "hong@example.test", "도용 의심", Instant.now())))
                .given(blocklistEntries)
                .findByIdentifierTypeAndIdentifierValue("BUYER_EMAIL", "hong@example.test");

        var result = queryService.detail(1L);

        assertThat(result.blocklistCandidates())
                .filteredOn(candidate -> candidate.blocked())
                .extracting(candidate -> candidate.identifierType())
                .containsExactly("BUYER_EMAIL");
    }

    // 경보 상세 응답 어디에도 IP · 기기 ID · 카드 토큰 원문이 없다 — #628 전에 원문으로 저장한 R02 · R03 · R07 근거 포함 (ISMS-P 2.6.3)
    @Test
    void detail_withRawIdentifiers_responseHasNoRawIpDeviceOrToken() {
        var evaluation = evaluation(101L, FdsResult.DENY);
        evaluation.addRuleHit(
                "R02",
                FdsRuleHitOutcome.MATCH,
                25,
                "신규 단말기",
                "{\"requiresPriorApproved\":true}",
                "{\"deviceId\":\"device-test-1\",\"approvedHistoryCount\":1}",
                null,
                null);
        evaluation.addRuleHit(
                "R03",
                FdsRuleHitOutcome.MATCH,
                25,
                "신규 카드",
                "{\"requiresPriorApproved\":true}",
                "{\"cardToken\":\"card-token-test-1\",\"approvedHistoryCount\":1}",
                null,
                null);
        evaluation.addRuleHit(
                "R07",
                FdsRuleHitOutcome.MATCH,
                100,
                "차단 목록",
                "{\"active\":true}",
                "{\"buyerRef\":\"hong-gildong\",\"deviceId\":\"device-test-1\",\"cardToken\":\"card-token-test-1\","
                        + "\"cardDisplayLabel\":\"VISA •••• 4242\",\"ipAddress\":\"203.0.113.10\","
                        + "\"matchedIdentifierTypes\":[\"IP_ADDRESS\"]}",
                null,
                null);
        var queryService = queryService();
        given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
        given(evaluationRepository.findByPaymentIdOrderByEvaluatedAtDesc(101L)).willReturn(List.of(evaluation));

        var result = queryService.detail(1L);

        assertThat(JsonMapper.builder().build().writeValueAsString(result))
                .doesNotContain("device-test-1", "card-token-test-1", "203.0.113.10");
        assertThat(result.blocklistCandidates())
                .extracting(candidate -> candidate.identifierType(), candidate -> candidate.displayValue())
                .contains(
                        tuple("CARD_TOKEN", "VISA •••• 4242"),
                        tuple("DEVICE_ID", "device-t…"),
                        tuple("IP_ADDRESS", "203.0.*.*"));
        assertThat(result.ruleHits())
                .extracting(hit -> hit.observedValue())
                .containsExactly(
                        "{\"approvedHistoryCount\":1,\"maskedDeviceId\":\"device-t…\"}",
                        "{\"approvedHistoryCount\":1,\"cardDisplayLabel\":\"VISA •••• 4242\"}",
                        "{\"buyerRef\":\"hong-gildong\",\"cardDisplayLabel\":\"VISA •••• 4242\","
                                + "\"matchedIdentifierTypes\":[\"IP_ADDRESS\"],\"maskedDeviceId\":\"device-t…\","
                                + "\"maskedIpAddress\":\"203.0.*.*\"}");
    }

    // 카드 표시값이 없는 평가는 차단 후보에 토큰을 대신 보내지 않는다 (#628 전에는 토큰 원문이 나갔다)
    @Test
    void detail_whenCardHasNoDisplayLabel_omitsToken() {
        var requestedAt = Instant.now();
        var evaluation = FdsEvaluation.completed(
                FdsEvaluationRequest.builder()
                        .evaluationRequestId(UUID.randomUUID())
                        .paymentId(101L)
                        .merchantId(1L)
                        .customerRef("hong-gildong")
                        .amount(150_000L)
                        .paymentRequestedAt(requestedAt)
                        .cardToken("card-token-test-1")
                        .build(),
                FdsResult.ALLOW,
                0,
                requestedAt.plusSeconds(1));
        var queryService = queryService();
        given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
        given(evaluationRepository.findByPaymentIdOrderByEvaluatedAtDesc(101L)).willReturn(List.of(evaluation));

        var result = queryService.detail(1L);

        assertThat(result.blocklistCandidates())
                .filteredOn(candidate -> candidate.identifierType().equals("CARD_TOKEN"))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.displayValue()).isNull());
        assertThat(JsonMapper.builder().build().writeValueAsString(result)).doesNotContain("card-token-test-1");
    }

    // 없는 평가 ID는 조회 실패 예외를 반환한다.
    @Test
    void detail_missingEvaluation_throwsNotFoundException() {
        var queryService = queryService();
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
        var queryService = queryService();

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
        var request = new FdsEvaluationRequest(
                UUID.randomUUID(),
                paymentId,
                1L,
                "hong-gildong",
                150_000L,
                requestedAt,
                "device-test-1",
                "card-token-test-1",
                "VISA •••• 4242",
                "KR",
                "203.0.113.10",
                "hong@example.test");
        return FdsEvaluation.completed(
                request, result, result == FdsResult.REVIEW ? 60 : 0, requestedAt.plusSeconds(1));
    }

    private FdsEvaluationQueryService queryService() {
        return new FdsEvaluationQueryService(evaluationRepository, failureAnalysisRepository, blocklistEntries);
    }
}
