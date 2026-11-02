package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsMlInput;
import com.donworry.fds.api.FdsMlTransaction;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FdsMlInputQueryServiceTests {

    private static final Long MERCHANT = 1L;
    private static final String CUSTOMER = "1:member-77";
    private static final Instant AT = Instant.parse("2026-10-30T03:00:00Z");

    @Mock
    private FdsEvaluationRepository evaluationRepository;

    // 재평가로 평가가 여러 행인 결제는 이력에 한 번만 넣고, 대상 결제 자신은 뺀다 (#409)
    @Test
    void findInput_reevaluatedPayment_onePerPayment() {
        var target = row(10L, 100L, CUSTOMER, "tok_a", "dev_a", FdsResult.ALLOW, AT);
        givenTarget(target);
        givenCustomerHistory(
                target,
                row(11L, 101L, CUSTOMER, "tok_a", "dev_a", FdsResult.ERROR, AT.minusSeconds(600)),
                row(12L, 101L, CUSTOMER, "tok_a", "dev_a", FdsResult.ALLOW, AT.minusSeconds(600)),
                row(13L, 102L, CUSTOMER, "tok_a", "dev_a", FdsResult.DENY, AT.minusSeconds(300)));
        givenSameCard(target);

        var input = service().findInput(10L, 2_000).orElseThrow();

        assertThat(input.customerHistory())
                .extracting(FdsMlTransaction::paymentId)
                .containsExactly(101L, 102L);
    }

    // 카드 토큰 · 기기 ID · 고객 번호 원문은 응답 어디에도 없다 (#409, #352 원문 비공개 선례)
    @Test
    void findInput_aliases_hideRawIdentifiers() {
        var target = row(10L, 100L, CUSTOMER, "tok_raw_target", "dev_raw_target", FdsResult.REVIEW, AT);
        givenTarget(target);
        givenCustomerHistory(
                target,
                row(11L, 101L, CUSTOMER, "tok_raw_target", "dev_raw_old", FdsResult.ALLOW, AT.minusSeconds(60)));
        givenSameCard(
                target,
                row(
                        21L,
                        201L,
                        "1:member-88",
                        "tok_raw_target",
                        "dev_raw_other",
                        FdsResult.ALLOW,
                        AT.minusSeconds(120)));

        var input = service().findInput(10L, 2_000).orElseThrow();

        assertThat(input.sharedCardHistory()).hasSize(1);
        assertThat(input.toString())
                .doesNotContain(
                        "tok_raw_target", "dev_raw_target", "dev_raw_old", "dev_raw_other", "member-77", "member-88");
    }

    // 같은 원문은 응답 안에서 같은 별칭, 다른 원문은 다른 별칭이다 — 어긋나면 cardSeenBefore · 도용 신호가 틀어진다 (#409)
    @Test
    void findInput_aliases_consistentWithinResponse() {
        var target = row(10L, 100L, CUSTOMER, "tok_a", "dev_x", FdsResult.ALLOW, AT);
        givenTarget(target);
        givenCustomerHistory(
                target,
                row(11L, 101L, CUSTOMER, "tok_a", "dev_y", FdsResult.ALLOW, AT.minusSeconds(600)),
                row(12L, 102L, CUSTOMER, "tok_b", "dev_x", FdsResult.ALLOW, AT.minusSeconds(300)));
        givenSameCard(
                target,
                row(21L, 201L, "1:other-a", "tok_a", "dev_p", FdsResult.ALLOW, AT.minusSeconds(900)),
                row(22L, 202L, "1:other-b", "tok_a", "dev_q", FdsResult.DENY, AT.minusSeconds(800)));

        var input = service().findInput(10L, 2_000).orElseThrow();

        var me = input.target();
        var sameCard = input.customerHistory().get(0);
        var sameDevice = input.customerHistory().get(1);
        var otherA = input.sharedCardHistory().get(0);
        var otherB = input.sharedCardHistory().get(1);
        assertThat(List.of(sameCard.cardKey(), otherA.cardKey(), otherB.cardKey()))
                .containsOnly(me.cardKey());
        assertThat(sameDevice.cardKey()).isNotEqualTo(me.cardKey());
        assertThat(sameDevice.deviceKey()).isEqualTo(me.deviceKey());
        assertThat(sameCard.deviceKey()).isNotEqualTo(me.deviceKey());
        assertThat(List.of(sameCard.customerKey(), sameDevice.customerKey())).containsOnly(me.customerKey());
        assertThat(otherA.customerKey()).isNotEqualTo(me.customerKey()).isNotEqualTo(otherB.customerKey());
    }

    // 이력이 상한을 넘으면 잘라 보내지 않고 표시만 한다. 상한과 같으면 그대로 보낸다 (#409)
    @Test
    void findInput_overLimit_flagged() {
        var target = row(10L, 100L, CUSTOMER, "tok_a", "dev_a", FdsResult.ALLOW, AT);
        given(evaluationRepository.findById(10L)).willReturn(Optional.of(target));
        given(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtLessThanEqual(
                        MERCHANT, CUSTOMER, AT))
                .willReturn(List.of(
                        row(11L, 101L, CUSTOMER, "tok_a", "dev_a", FdsResult.ALLOW, AT.minusSeconds(600)),
                        row(12L, 102L, CUSTOMER, "tok_a", "dev_a", FdsResult.ALLOW, AT.minusSeconds(300))));
        given(evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(
                        List.of(row(21L, 201L, "1:other", "tok_a", "dev_o", FdsResult.ALLOW, AT.minusSeconds(900))));

        FdsMlInput over = service().findInput(10L, 2).orElseThrow();
        FdsMlInput atLimit = service().findInput(10L, 3).orElseThrow();

        assertThat(over.historyOverLimit()).isTrue();
        assertThat(over.target().paymentId()).isEqualTo(100L);
        assertThat(over.customerHistory()).isEmpty();
        assertThat(over.sharedCardHistory()).isEmpty();
        assertThat(atLimit.historyOverLimit()).isFalse();
        assertThat(atLimit.customerHistory()).hasSize(2);
        assertThat(atLimit.sharedCardHistory()).hasSize(1);
    }

    // DB는 대소문자를 구분하지 않아 TOK_A도 같은 카드로 돌려준다 — 원문이 정확히 같은 카드만 같은 카드 이력에 넣는다 (Codex 재현)
    @Test
    void findInput_caseOnlyDifferentCardToken_notShared() {
        var target = row(10L, 100L, CUSTOMER, "tok_a", "dev_a", FdsResult.ALLOW, AT);
        givenTarget(target);
        givenCustomerHistory(target);
        givenSameCard(
                target,
                row(21L, 201L, "1:other-upper", "TOK_A", "dev_o", FdsResult.ALLOW, AT.minusSeconds(60)),
                row(22L, 202L, "1:other-same", "tok_a", "dev_p", FdsResult.ALLOW, AT.minusSeconds(120)));

        var input = service().findInput(10L, 2_000).orElseThrow();

        assertThat(input.sharedCardHistory())
                .extracting(FdsMlTransaction::paymentId)
                .containsExactly(202L);
    }

    // 고객 번호도 대소문자만 다르면 DB가 같은 고객으로 돌려준다 — 정확히 같은 고객만 고객 이력에 넣는다 (Codex 같은 원인)
    @Test
    void findInput_caseOnlyDifferentCustomerRef_notInCustomerHistory() {
        var target = row(10L, 100L, CUSTOMER, "tok_a", "dev_a", FdsResult.ALLOW, AT);
        givenTarget(target);
        givenCustomerHistory(
                target,
                row(11L, 101L, "1:MEMBER-77", "tok_b", "dev_b", FdsResult.ALLOW, AT.minusSeconds(600)),
                row(12L, 102L, CUSTOMER, "tok_c", "dev_c", FdsResult.ALLOW, AT.minusSeconds(300)));
        givenSameCard(target);

        var input = service().findInput(10L, 2_000).orElseThrow();

        assertThat(input.customerHistory())
                .extracting(FdsMlTransaction::paymentId)
                .containsExactly(102L);
    }

    private FdsMlInputQueryService service() {
        return new FdsMlInputQueryService(evaluationRepository);
    }

    private void givenTarget(FdsEvaluation target) {
        given(evaluationRepository.findById(target.getEvaluationId())).willReturn(Optional.of(target));
    }

    private void givenCustomerHistory(FdsEvaluation target, FdsEvaluation... rows) {
        given(evaluationRepository.findByMerchantIdAndCustomerRefAndPaymentRequestedAtLessThanEqual(
                        MERCHANT, target.getCustomerRef(), target.getPaymentRequestedAt()))
                .willReturn(List.of(rows));
    }

    // 실제 쿼리처럼 대상 자신도 같은 카드 결과에 들어 있다 — 같은 고객이라 걸러져야 한다
    private void givenSameCard(FdsEvaluation target, FdsEvaluation... others) {
        var rows = new ArrayList<FdsEvaluation>(List.of(others));
        rows.add(target);
        given(evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        eq(MERCHANT), anyCollection(), any()))
                .willReturn(rows);
    }

    private static FdsEvaluation row(
            Long evaluationId,
            Long paymentId,
            String customerRef,
            String cardToken,
            String deviceId,
            FdsResult result,
            Instant requestedAt) {
        var request = FdsEvaluationRequest.builder()
                .evaluationRequestId(UUID.randomUUID())
                .paymentId(paymentId)
                .merchantId(MERCHANT)
                .customerRef(customerRef)
                .amount(30_000L)
                .paymentRequestedAt(requestedAt)
                .cardToken(cardToken)
                .deviceId(deviceId)
                .build();
        var evaluation = result == FdsResult.ERROR
                ? FdsEvaluation.failed(request, "TEST_ERROR", requestedAt)
                : FdsEvaluation.completed(request, result, 40, requestedAt);
        ReflectionTestUtils.setField(evaluation, "evaluationId", evaluationId);
        return evaluation;
    }
}
