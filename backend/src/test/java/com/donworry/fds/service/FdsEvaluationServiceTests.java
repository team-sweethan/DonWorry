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
import com.donworry.fds.entity.FdsBlocklistEntry;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.entity.FdsRulePolicy;
import com.donworry.fds.repository.FdsBlocklistEntryRepository;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.fds.repository.FdsRulePolicyRepository;
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
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class FdsEvaluationServiceTests {

    @Mock
    private FdsEvaluationRepository evaluationRepository;

    @Mock
    private FdsEvaluationTransactionService transactionService;

    @Mock
    private FdsRulePolicyRepository rulePolicies;

    @Mock
    private FdsBlocklistEntryRepository blocklistEntries;

    @Mock
    private FdsRulePolicy rulePolicy;

    @Mock
    private FdsBlocklistEntry blocklistEntry;

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

        var result = transactionEvaluator.evaluate(request(3L, 500_000L, requestedAt));

        assertThat(result.result()).isEqualTo(FdsResult.REVIEW);
        assertThat(result.riskScore()).isEqualTo(60);
        assertThat(result.evaluationId()).isEqualTo(1L);
        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        assertThat(evaluationCaptor.getValue().getCustomerRef()).isEqualTo("hong-gildong");
        assertThat(evaluationCaptor.getValue().getMerchantId()).isEqualTo(1L);
        assertThat(evaluationCaptor.getValue().getPaymentRequestedAt()).isEqualTo(requestedAt);
        assertThat(evaluationCaptor.getValue().getRequestedAmount()).isEqualTo(500_000L);
        assertThat(evaluationCaptor.getValue().getRuleHits()).hasSize(8);
        assertThat(evaluationCaptor.getValue().getRuleHits().getFirst().getObservedValue())
                .isEqualTo("{\"amount\":500000}");
        assertThat(evaluationCaptor.getValue().getRuleHits().getFirst().getThresholdSnapshot())
                .isEqualTo("{\"amountGte\":500000,\"score\":30}");
        assertThat(evaluationCaptor.getValue().getRuleHits().get(1).getObservedValue())
                .isEqualTo("{\"attemptCount\":3}");
    }

    @Test
    // 꺼진 룰은 입력이 기준을 충족해도 점수와 적중 결과에 반영하지 않는다.
    void evaluate_whenHighAmountRuleIsInactive_doesNotApplyRule() {
        var requestedAt = Instant.parse("2026-10-22T01:00:00Z");
        var policyEvaluator = new FdsEvaluationTransactionService(
                evaluationRepository,
                null,
                null,
                rulePolicies,
                JsonMapper.builder().build());
        when(evaluationRepository.findByEvaluationRequestId(any())).thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        any(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluationRepository.findByMerchantIdAndCustomerRef(any(), any())).thenReturn(List.of());
        when(rulePolicies.findByRuleCode("R01")).thenReturn(Optional.of(rulePolicy));
        when(rulePolicy.isActive()).thenReturn(false);
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        var result = policyEvaluator.evaluate(request(1L, 500_000L, requestedAt));

        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        var highAmount = evaluationCaptor.getValue().getRuleHits().stream()
                .filter(hit -> hit.getRuleCode().equals("R01"))
                .findFirst()
                .orElseThrow();
        assertThat(result.result()).isEqualTo(FdsResult.ALLOW);
        assertThat(result.riskScore()).isZero();
        assertThat(highAmount.getOutcome().name()).isEqualTo("NO_MATCH");
        assertThat(highAmount.getAppliedScore()).isZero();
    }

    @Test
    // 활성 차단 목록과 일치하면 다른 점수 계산과 무관하게 R07이 즉시 DENY를 반환한다.
    void evaluate_whenBuyerRefIsOnActiveBlocklist_returnsDeny() {
        var requestedAt = Instant.parse("2026-10-22T01:00:00Z");
        var blocklistEvaluator = new FdsEvaluationTransactionService(
                evaluationRepository,
                null,
                blocklistEntries,
                null,
                JsonMapper.builder().build());
        when(evaluationRepository.findByEvaluationRequestId(any())).thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        any(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluationRepository.findByMerchantIdAndCustomerRef(any(), any())).thenReturn(List.of());
        when(blocklistEntries.findByIdentifierTypeAndIdentifierValue("BUYER_REF", "hong-gildong"))
                .thenReturn(List.of(blocklistEntry));
        when(blocklistEntry.isActiveAt(requestedAt)).thenReturn(true);
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        var result = blocklistEvaluator.evaluate(request(1L, 10_000L, requestedAt));

        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        var blocklist = evaluationCaptor.getValue().getRuleHits().stream()
                .filter(hit -> hit.getRuleCode().equals("R07"))
                .findFirst()
                .orElseThrow();
        assertThat(result.result()).isEqualTo(FdsResult.DENY);
        assertThat(result.riskScore()).isEqualTo(100);
        assertThat(blocklist.getOutcome().name()).isEqualTo("MATCH");
        assertThat(blocklist.getAppliedScore()).isEqualTo(100);
        assertThat(blocklist.getObservedValue()).contains("\"matchedIdentifierTypes\":[\"BUYER_REF\"]");
    }

    @Test
    // 카드와 IP가 함께 차단 목록에 있으면 R07 스냅샷에 두 근거를 모두 남긴다.
    void evaluate_whenCardAndIpAreOnBlocklist_recordsBothMatchedIdentifierTypes() {
        var requestedAt = Instant.parse("2026-10-22T01:00:00Z");
        var blocklistEvaluator = new FdsEvaluationTransactionService(
                evaluationRepository,
                null,
                blocklistEntries,
                null,
                JsonMapper.builder().build());
        var request = new FdsEvaluationRequest(
                UUID.randomUUID(),
                1L,
                1L,
                "hong-gildong",
                10_000L,
                requestedAt,
                "device-1",
                "card-token-4242",
                "VISA •••• 4242",
                "KR",
                "203.0.113.10",
                "hong@example.test");
        when(evaluationRepository.findByEvaluationRequestId(any())).thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        any(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluationRepository.findByMerchantIdAndCustomerRef(any(), any())).thenReturn(List.of());
        when(blocklistEntries.findByIdentifierTypeAndIdentifierValue(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
        org.mockito.Mockito.doReturn(List.of(blocklistEntry))
                .when(blocklistEntries)
                .findByIdentifierTypeAndIdentifierValue("CARD_TOKEN", "card-token-4242");
        org.mockito.Mockito.doReturn(List.of(blocklistEntry))
                .when(blocklistEntries)
                .findByIdentifierTypeAndIdentifierValue("IP_ADDRESS", "203.0.113.10");
        when(blocklistEntry.isActiveAt(requestedAt)).thenReturn(true);
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        blocklistEvaluator.evaluate(request);

        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        var blocklist = evaluationCaptor.getValue().getRuleHits().stream()
                .filter(hit -> hit.getRuleCode().equals("R07"))
                .findFirst()
                .orElseThrow();
        assertThat(blocklist.getObservedValue()).contains("\"matchedIdentifierTypes\":[\"CARD_TOKEN\",\"IP_ADDRESS\"]");
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

        assertThat(result.riskScore()).isZero();
        assertThat(result.result()).isEqualTo(FdsResult.ALLOW);
    }

    @Test
    // R06은 평가 요청 시점 이후의 결제 이력을 카드 테스팅 집계에 포함하지 않는다.
    void evaluate_cardTestingExcludesFuturePaymentHistory() {
        var requestedAt = Instant.parse("2026-10-22T01:09:00Z");
        var request = request(10L, "customer", 1_000L, requestedAt, "card-current");
        when(evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId()))
                .thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        any(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluationRepository.findByMerchantIdAndCustomerRef(1L, "customer"))
                .thenReturn(List.of(
                        declinedEvaluation(1L, "customer", "card-a", requestedAt.plusSeconds(60)),
                        declinedEvaluation(2L, "customer", "card-b", requestedAt.plusSeconds(120)),
                        declinedEvaluation(3L, "customer", "card-c", requestedAt.plusSeconds(180)),
                        declinedEvaluation(4L, "customer", "card-c", requestedAt.plusSeconds(240))));
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        transactionEvaluator.evaluate(request);

        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        var cardTesting = evaluationCaptor.getValue().getRuleHits().stream()
                .filter(hit -> hit.getRuleCode().equals("R06"))
                .findFirst()
                .orElseThrow();
        assertThat(cardTesting.getOutcome().name()).isEqualTo("NO_MATCH");
        assertThat(cardTesting.getObservedValue())
                .contains("\"smallAttemptCount\":1", "\"cardCount\":1", "\"declinedCount\":0");
    }

    @Test
    // 재평가된 동일 결제는 R06 소액 시도 수에서 한 번만 센다.
    void evaluate_cardTestingCountsCurrentPaymentOnlyOnce() {
        var requestedAt = Instant.parse("2026-10-22T01:09:00Z");
        var request = request(1L, "customer", 1_000L, requestedAt, "card-a");
        when(evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId()))
                .thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        any(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluationRepository.findByMerchantIdAndCustomerRef(1L, "customer"))
                .thenReturn(List.of(
                        declinedEvaluation(1L, "customer", "card-a", requestedAt.minusSeconds(60)),
                        declinedEvaluation(2L, "customer", "card-b", requestedAt.minusSeconds(120)),
                        declinedEvaluation(3L, "customer", "card-c", requestedAt.minusSeconds(180)),
                        declinedEvaluation(4L, "customer", "card-c", requestedAt.minusSeconds(240))));
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        transactionEvaluator.evaluate(request);

        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        var cardTesting = evaluationCaptor.getValue().getRuleHits().stream()
                .filter(hit -> hit.getRuleCode().equals("R06"))
                .findFirst()
                .orElseThrow();
        assertThat(cardTesting.getOutcome().name()).isEqualTo("NO_MATCH");
        assertThat(cardTesting.getObservedValue()).contains("\"smallAttemptCount\":4");
    }

    // R08은 같은 구매자의 이전 실제 거절 결제 2건이 10분 안에 있으면 적중한다.
    @Test
    void evaluate_twoPriorDeclinesWithinWindow_matchesDeclinedRetryRule() {
        var requestedAt = Instant.parse("2026-10-22T01:09:00Z");
        var request = request(3L, "customer", 50_000L, requestedAt, "card-current");
        when(evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId()))
                .thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        any(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluationRepository.findByMerchantIdAndCustomerRef(1L, "customer"))
                .thenReturn(List.of(
                        declinedEvaluation(1L, "customer", "card-a", requestedAt.minusSeconds(60)),
                        declinedEvaluation(2L, "customer", "card-b", requestedAt.minusSeconds(120))));
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        var result = transactionEvaluator.evaluate(request);

        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        var declinedRetry = evaluationCaptor.getValue().getRuleHits().stream()
                .filter(hit -> hit.getRuleCode().equals("R08"))
                .findFirst()
                .orElseThrow();
        assertThat(result.riskScore()).isEqualTo(35);
        assertThat(declinedRetry.getOutcome().name()).isEqualTo("MATCH");
        assertThat(declinedRetry.getAppliedScore()).isEqualTo(35);
        assertThat(declinedRetry.getObservedValue()).isEqualTo("{\"declinedCount\":2}");
    }

    @Test
    // 제어 문자가 포함된 고객 식별자는 JSON 스냅샷에서도 유효한 문자열로 저장한다.
    void evaluate_customerRefWithControlCharacters_escapesRuleSnapshotJson() {
        var request = request(1L, "customer\n\tref", 1_000L, Instant.parse("2026-10-22T01:00:00Z"), null);
        when(evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId()))
                .thenReturn(Optional.empty());
        when(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        any(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluationRepository.findByMerchantIdAndCustomerRef(1L, request.customerRef()))
                .thenReturn(List.of());
        when(evaluationRepository.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 1L);
            return evaluation;
        });

        transactionEvaluator.evaluate(request);

        verify(evaluationRepository).saveAndFlush(evaluationCaptor.capture());
        var blocklist = evaluationCaptor.getValue().getRuleHits().stream()
                .filter(hit -> hit.getRuleCode().equals("R07"))
                .findFirst()
                .orElseThrow();
        assertThat(blocklist.getObservedValue()).contains("\"buyerRef\":\"customer\\n\\tref\"");
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

    private FdsEvaluation declinedEvaluation(
            Long paymentId, String customerRef, String cardToken, Instant requestedAt) {
        var evaluation = FdsEvaluation.completed(
                request(paymentId, customerRef, 1_000L, requestedAt, cardToken), FdsResult.ALLOW, 0, requestedAt);
        evaluation.recordAuthorizationResult("DECLINED");
        return evaluation;
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

    private FdsEvaluationRequest request(
            Long paymentId, String customerRef, long amount, Instant requestedAt, String cardToken) {
        return new FdsEvaluationRequest(
                UUID.randomUUID(), paymentId, 1L, customerRef, amount, requestedAt, null, cardToken, null);
    }
}
