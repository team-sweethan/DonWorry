package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsLinkedPayments.Match;
import com.donworry.fds.api.FdsLinkedPayments.Nearby;
import com.donworry.fds.api.FdsLinkedPayments.PaymentMatch;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FdsLinkedPaymentQueryServiceTests {

    private static final Long MERCHANT = 1L;
    private static final String CASE_CUSTOMER = "case-customer";
    private static final Instant SINCE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-10-26T01:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(1);

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

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE, WINDOW);

        assertThat(result.card())
                .isEqualTo(Match.builder()
                        .provided(true)
                        .otherCustomerCount(2)
                        .paymentCount(3)
                        .resultCounts(Map.of(FdsResult.ALLOW, 1, FdsResult.REVIEW, 1, FdsResult.DENY, 1))
                        .declinedCount(1)
                        .nearby(Nearby.builder()
                                .otherCustomerCount(2)
                                .paymentCount(3)
                                .resultCounts(Map.of(FdsResult.ALLOW, 1, FdsResult.REVIEW, 1, FdsResult.DENY, 1))
                                .declinedCount(1)
                                .build())
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

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE, WINDOW);

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

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE, WINDOW);

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

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE, WINDOW);

        assertThat(result.device())
                .isEqualTo(Match.builder()
                        .provided(true)
                        .otherCustomerCount(1)
                        .paymentCount(1)
                        .resultCounts(Map.of(FdsResult.DENY, 1))
                        .declinedCount(0)
                        .nearby(Nearby.builder()
                                .otherCustomerCount(1)
                                .paymentCount(1)
                                .resultCounts(Map.of(FdsResult.DENY, 1))
                                .declinedCount(0)
                                .build())
                        .build());
    }

    // 사건 결제마다 그 결제의 카드 · 기기 · IP와 겹친 다른 고객 결제만 센다 (#533)
    // 결제 100만 공유 카드를 썼으면 결제 101에는 겹침이 없다. 평가가 없는 결제 102는 정보 없음
    @Test
    void findLinked_twoCasePayments_countsPerPayment() {
        givenCaseEvaluations(
                evaluation(100L, CASE_CUSTOMER, "tok_shared", null, FdsResult.REVIEW, AT),
                evaluation(101L, CASE_CUSTOMER, "tok_own", null, FdsResult.REVIEW, AT));
        given(evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(List.of(evaluation(201L, "other-1", "tok_shared", null, FdsResult.REVIEW, AT)));

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L, 101L, 102L), SINCE, WINDOW);

        assertThat(result.card().paymentCount()).isEqualTo(1);
        assertThat(result.payments()).extracting(PaymentMatch::paymentId).containsExactly(100L, 101L, 102L);
        assertThat(result.payments().get(0).card().paymentCount()).isEqualTo(1);
        assertThat(result.payments().get(1).card().provided()).isTrue();
        assertThat(result.payments().get(1).card().paymentCount()).isZero();
        assertThat(result.payments().get(2).card()).isEqualTo(Match.notProvided());
        assertThat(result.toString()).doesNotContain("tok_shared", "tok_own", "other-1");
    }

    // 사건 결제 앞뒤 1시간 안의 다른 고객 결제만 nearby로 센다 — 1시간 밖은 30일 합계에만 든다 (#637)
    // 공격(짧은 시간에 몰림)과 공용 와이파이(한 달에 흩어짐)를 가르는 숫자다. 경계 포함: 정확히 60분은 안, 61분은 밖
    @Test
    void findLinked_otherCustomersInAndOutOfWindow_nearbyCountsOnlyWithinWindow() {
        givenCaseEvaluations(
                requestedAt(evaluation(100L, CASE_CUSTOMER, "tok_shared", null, FdsResult.REVIEW, AT), AT));
        var before = requestedAt(
                evaluation(201L, "other-1", "tok_shared", null, FdsResult.REVIEW, AT),
                AT.minus(Duration.ofMinutes(60)));
        before.recordAuthorizationResult("DECLINED");
        given(evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(List.of(
                        before,
                        requestedAt(
                                evaluation(202L, "other-2", "tok_shared", null, FdsResult.DENY, AT),
                                AT.plus(Duration.ofMinutes(30))),
                        requestedAt(
                                evaluation(203L, "other-3", "tok_shared", null, FdsResult.ALLOW, AT),
                                AT.plus(Duration.ofMinutes(61))),
                        requestedAt(
                                evaluation(204L, "other-4", "tok_shared", null, FdsResult.ALLOW, AT),
                                AT.minus(Duration.ofDays(10)))));

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L), SINCE, WINDOW);

        assertThat(result.card().paymentCount()).isEqualTo(4);
        assertThat(result.card().otherCustomerCount()).isEqualTo(4);
        assertThat(result.card().nearby())
                .isEqualTo(Nearby.builder()
                        .otherCustomerCount(2)
                        .paymentCount(2)
                        .resultCounts(Map.of(FdsResult.REVIEW, 1, FdsResult.DENY, 1))
                        .declinedCount(1)
                        .build());
        assertThat(result.payments().get(0).card().nearby().paymentCount()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain("tok_shared", "other-1", "other-2");
    }

    // 결제별 nearby는 그 결제의 요청 시각으로 센다 — 결제 101 근처 결제가 결제 100에 "몰림"으로 붙지 않는다 (#637)
    // 사건 전체는 같은 값을 쓴 사건 결제 중 하나라도 앞뒤 1시간 안이면 센다
    @Test
    void findLinked_twoCasePayments_nearbyUsesEachPaymentTime() {
        Instant later = AT.plus(Duration.ofHours(5));
        givenCaseEvaluations(
                requestedAt(evaluation(100L, CASE_CUSTOMER, "tok_shared", null, FdsResult.REVIEW, AT), AT),
                requestedAt(evaluation(101L, CASE_CUSTOMER, "tok_shared", null, FdsResult.REVIEW, AT), later));
        given(evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(List.of(requestedAt(
                        evaluation(201L, "other-1", "tok_shared", null, FdsResult.REVIEW, AT),
                        later.plus(Duration.ofMinutes(10)))));

        var result = service().findLinked(MERCHANT, CASE_CUSTOMER, List.of(100L, 101L), SINCE, WINDOW);

        assertThat(result.card().nearby().paymentCount()).isEqualTo(1);
        assertThat(result.payments())
                .extracting(p -> p.card().paymentCount(), p -> p.card().nearby().paymentCount())
                .containsExactly(tuple(1, 0), tuple(1, 1));
    }

    private FdsLinkedPaymentQueryService service() {
        return new FdsLinkedPaymentQueryService(evaluationRepository);
    }

    private void givenCaseEvaluations(FdsEvaluation... evaluations) {
        given(evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(anyCollection()))
                .willReturn(List.of(evaluations));
    }

    // 결제 요청 시각만 바꾼다 — 앞뒤 시간 집계는 평가 시각이 아니라 이 값으로 비교한다
    private static FdsEvaluation requestedAt(FdsEvaluation evaluation, Instant requestedAt) {
        ReflectionTestUtils.setField(evaluation, "paymentRequestedAt", requestedAt);
        return evaluation;
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
                .cardToken(cardToken == null ? "card-token-" + paymentId : cardToken)
                .deviceId(deviceId == null ? "device-" + paymentId : deviceId)
                .cardDisplayLabel("테스트 **** 0001")
                .ipCountryCode("KR")
                .ipAddress("203.0.113.10")
                .buyerEmail("buyer@example.test")
                .build();
        var evaluation = FdsEvaluation.completed(request, result, result == FdsResult.ERROR ? 0 : 60, at);
        // 신규 평가는 식별자를 모두 받지만, 이 테스트는 과거 이력의 누락값도 조회할 수 있어야 한다.
        ReflectionTestUtils.setField(evaluation, "cardToken", cardToken);
        if (cardToken == null) ReflectionTestUtils.setField(evaluation, "cardDisplayLabel", null);
        ReflectionTestUtils.setField(evaluation, "deviceId", deviceId);
        ReflectionTestUtils.setField(evaluation, "ipAddress", null);
        return evaluation;
    }
}
