package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.donworry.fds.api.FdsEvidenceReader;
import com.donworry.fds.api.FdsPaymentEvidence;
import com.donworry.fds.api.FdsPaymentEvidence.MatchedRule;
import com.donworry.fds.api.FdsResult;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import com.donworry.investigation.service.mock.MockPaymentInfoStore;
import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// P5 결제 정보와 P2 평가 근거를 결제별로 합치는 규칙 (#163) — 한쪽만 있으면 그쪽 값만 채우고, 조회 오류는 숨기지 않는다
class PaymentFdsInfoReaderTests {

    static final Instant REQUESTED_AT = Instant.parse("2026-10-26T05:00:00Z");

    PaymentReader paymentReader = mock(PaymentReader.class);
    FdsEvidenceReader fdsEvidenceReader = mock(FdsEvidenceReader.class);
    MockPaymentInfoStore mockPaymentInfoStore = new MockPaymentInfoStore();
    PaymentFdsInfoReader reader;

    @BeforeEach
    void setUp() {
        reader = new PaymentFdsInfoReader(paymentReader, fdsEvidenceReader, mockPaymentInfoStore);
        given(paymentReader.findById(anyLong())).willReturn(Optional.empty());
        given(fdsEvidenceReader.findLatestByPaymentIds(any())).willReturn(List.of());
    }

    // 결제와 평가가 모두 있으면 P5 · P2 값을 합치고 적중 룰은 코드 · 이름 · 점수로 옮긴다
    @Test
    void findAll_withPaymentAndEvaluation_mergesBoth() {
        given(paymentReader.findById(1L)).willReturn(Optional.of(payment(1L)));
        given(fdsEvidenceReader.findLatestByPaymentIds(List.of(1L)))
                .willReturn(List.of(
                        evidence(1L, FdsResult.REVIEW, 65, new MatchedRule("HIGH_AMOUNT", "고액 결제", 35, null, null))));

        assertThat(reader.findAll(List.of(1L)))
                .containsExactly(Map.entry(
                        1L,
                        new PaymentInfo(
                                1L,
                                150_000L,
                                "HELD",
                                REQUESTED_AT,
                                65,
                                "REVIEW",
                                List.of(new RuleHit("HIGH_AMOUNT", "고액 결제", 35)))));
    }

    // P2 평가가 없으면 결제 값만 채우고 점수 · 판정은 null, 적중 룰은 비운다
    @Test
    void findAll_withoutEvaluation_leavesFdsEmpty() {
        given(paymentReader.findById(1L)).willReturn(Optional.of(payment(1L)));

        assertThat(reader.findAll(List.of(1L)).get(1L))
                .isEqualTo(new PaymentInfo(1L, 150_000L, "HELD", REQUESTED_AT, null, null, List.of()));
    }

    // P5에 결제가 없으면 평가 값만 채우고 금액 · 상태 · 요청 시각은 null이다
    @Test
    void findAll_withoutPayment_leavesPaymentEmpty() {
        given(fdsEvidenceReader.findLatestByPaymentIds(List.of(1L)))
                .willReturn(List.of(evidence(1L, FdsResult.REVIEW, 65)));

        assertThat(reader.findAll(List.of(1L)).get(1L))
                .isEqualTo(new PaymentInfo(1L, null, null, null, 65, "REVIEW", List.of()));
    }

    // 평가가 모두 ERROR면 점수 null · 판정 ERROR · 적중 룰 없음으로 보여 평가 없음과 구분한다
    @Test
    void findAll_withOnlyErrorEvaluation_showsErrorWithoutScore() {
        given(paymentReader.findById(1L)).willReturn(Optional.of(payment(1L)));
        given(fdsEvidenceReader.findLatestByPaymentIds(List.of(1L)))
                .willReturn(List.of(evidence(1L, FdsResult.ERROR, null)));

        PaymentInfo info = reader.findAll(List.of(1L)).get(1L);

        assertThat(info.riskScore()).isNull();
        assertThat(info.fdsResult()).isEqualTo("ERROR");
        assertThat(info.ruleHits()).isEmpty();
    }

    // P5 · P2 어디에도 없는 결제는 결과에서 빠진다
    @Test
    void findAll_withNeither_omitsPayment() {
        assertThat(reader.findAll(List.of(1L))).isEmpty();
    }

    // 시연 결제는 목 저장소에서 읽고 P5 · P2에는 묻지 않는다. 실제 결제와 섞여도 각자 읽는다
    @Test
    void findAll_withDemoAndRealPayments_readsEachFromItsSource() {
        PaymentInfo demo = mockPaymentInfoStore.registerHeld(300_000L, 65, List.of("NEW_CARD"), REQUESTED_AT);
        given(paymentReader.findById(1L)).willReturn(Optional.of(payment(1L)));

        Map<Long, PaymentInfo> found = reader.findAll(List.of(demo.paymentId(), 1L));

        assertThat(found.get(demo.paymentId())).isEqualTo(demo);
        assertThat(found.get(1L).amount()).isEqualTo(150_000L);
        verify(paymentReader, never()).findById(demo.paymentId());
        verify(fdsEvidenceReader).findLatestByPaymentIds(List.of(1L));
    }

    // 조회 오류는 null로 숨기지 않고 그대로 던진다 — 장애가 "값 모름"으로 보이면 빈 근거로 판정할 수 있다
    @Test
    void findAll_whenReaderFails_propagates() {
        given(paymentReader.findById(1L)).willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> reader.findAll(List.of(1L))).isInstanceOf(IllegalStateException.class);
    }

    private static PaymentSummary payment(Long paymentId) {
        return PaymentSummary.builder()
                .paymentId(paymentId)
                .amount(150_000L)
                .paymentStatus("HELD")
                .requestedAt(REQUESTED_AT)
                .build();
    }

    private static FdsPaymentEvidence evidence(
            Long paymentId, FdsResult result, Integer riskScore, MatchedRule... rules) {
        return new FdsPaymentEvidence(paymentId, 10L, result, riskScore, REQUESTED_AT, List.of(rules));
    }
}
