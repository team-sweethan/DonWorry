package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsLinkedPayments.Match;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FdsLinkedPaymentQueryServiceTests {

    private static final Long MERCHANT = 1L;
    private static final String CASE_CUSTOMER = "case-customer";
    private static final Instant SINCE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-10-26T01:00:00Z");

    @Mock
    private FdsEvaluationRepository evaluationRepository;

    // 같은 카드를 쓴 다른 고객 결제의 고객 수 · 결제 수 · 판정 · 카드사 거절을 세고, 원문은 내보내지 않는다 (#352)
    @Test
    void findLinked_otherCustomerSameCard_countsCustomersAndPayments() {
        var casePayment = evaluation(100L, CASE_CUSTOMER, "tok_shared", null, FdsResult.REVIEW, AT);
        var declined = evaluation(202L, "other-1", "tok_shared", null, FdsResult.ALLOW, AT);
        declined.recordAuthorizationResult("DECLINED");
        givenCaseEvaluations(casePayment);
        given(evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(List.of(
                        evaluation(201L, "other-1", "tok_shared", null, FdsResult.REVIEW, AT),
                        declined,
                        evaluation(203L, "other-2", "tok_shared", null, FdsResult.DENY, AT)));

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE);

        assertThat(result.card())
                .isEqualTo(Match.builder()
                        .provided(true)
                        .otherCustomerCount(2)
                        .paymentCount(3)
                        .resultCounts(Map.of(FdsResult.ALLOW, 1, FdsResult.REVIEW, 1, FdsResult.DENY, 1))
                        .declinedCount(1)
                        .build());
        assertThat(result.toString()).doesNotContain("tok_shared", "other-1", "other-2", CASE_CUSTOMER);
    }

    // 같은 고객의 다른 결제 · 사건 결제 자신은 "다른 고객 결제"로 세지 않는다 (#352)
    @Test
    void findLinked_sameCustomerOrCasePayments_notCounted() {
        var casePayment = evaluation(100L, CASE_CUSTOMER, "tok_shared", null, FdsResult.REVIEW, AT);
        givenCaseEvaluations(casePayment);
        given(evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(List.of(
                        casePayment,
                        evaluation(101L, CASE_CUSTOMER, "tok_shared", null, FdsResult.ALLOW, AT),
                        evaluation(201L, "other-1", "tok_shared", null, FdsResult.REVIEW, AT)));

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE);

        assertThat(result.card().otherCustomerCount()).isEqualTo(1);
        assertThat(result.card().paymentCount()).isEqualTo(1);
    }

    // 사건 결제가 IP를 보내지 않았으면 "같은 IP 없음"이 아니라 "정보 없음"으로 둔다 (#352)
    @Test
    void findLinked_caseHasNoIp_markedNotProvided() {
        givenCaseEvaluations(evaluation(100L, CASE_CUSTOMER, "tok_shared", null, FdsResult.REVIEW, AT));
        given(evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(List.of());

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE);

        assertThat(result.card().provided()).isTrue();
        assertThat(result.card().paymentCount()).isZero();
        assertThat(result.ip()).isEqualTo(Match.notProvided());
        assertThat(result.device()).isEqualTo(Match.notProvided());
    }

    // 재평가로 평가가 여러 개인 결제는 한 건으로 세고, 판정은 최신 비오류 평가를 쓴다 (#352)
    @Test
    void findLinked_reevaluatedPayment_countedOnceWithLatestResult() {
        givenCaseEvaluations(evaluation(100L, CASE_CUSTOMER, null, "device-shared", FdsResult.REVIEW, AT));
        given(evaluationRepository.findByMerchantIdAndDeviceIdInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(List.of(
                        evaluation(201L, "other-1", null, "device-shared", FdsResult.ALLOW, AT),
                        evaluation(201L, "other-1", null, "device-shared", FdsResult.DENY, AT.plusSeconds(60)),
                        evaluation(201L, "other-1", null, "device-shared", FdsResult.ERROR, AT.plusSeconds(120))));

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE);

        assertThat(result.device())
                .isEqualTo(Match.builder()
                        .provided(true)
                        .otherCustomerCount(1)
                        .paymentCount(1)
                        .resultCounts(Map.of(FdsResult.DENY, 1))
                        .declinedCount(0)
                        .build());
    }

    private FdsLinkedPaymentQueryService service() {
        return new FdsLinkedPaymentQueryService(evaluationRepository);
    }

    private void givenCaseEvaluations(FdsEvaluation... evaluations) {
        given(evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(anyCollection()))
                .willReturn(List.of(evaluations));
    }

    private static FdsEvaluation evaluation(
            Long paymentId, String customerRef, String cardToken, String deviceId, FdsResult result, Instant at) {
        var request = FdsEvaluationRequest.builder()
                .evaluationRequestId(UUID.randomUUID())
                .paymentId(paymentId)
                .merchantId(MERCHANT)
                .customerRef(customerRef)
                .amount(50_000L)
                .paymentRequestedAt(AT)
                .cardToken(cardToken)
                .deviceId(deviceId)
                .build();
        return FdsEvaluation.completed(request, result, result == FdsResult.ERROR ? 0 : 60, at);
    }
}
