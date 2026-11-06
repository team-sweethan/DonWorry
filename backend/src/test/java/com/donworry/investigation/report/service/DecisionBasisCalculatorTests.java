package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.investigation.report.dto.CasePaymentsToolResponse;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse.Basis;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse.Normal;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse.Signal;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse.RuleHit;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.Match;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.PaymentLinks;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 판정 근거 · 판정 기준표 (#501 → #618 Spring으로 옮김)
// 실측 사건 모양(v3 시험지 1011 · 1066 · 1015 · 1014)을 줄여 옮겼다 — 같은 근거면 언제나 같은 판정
// ai-server tests/test_decision_basis.py · test_linked_per_payment.py와 같은 사건 · 같은 기대값
class DecisionBasisCalculatorTests {

    static final Instant AT = Instant.parse("2026-05-21T11:16:43Z");

    record Row(long amount, String status, String at) {}

    static final List<Row> USUAL = IntStream.range(0, 5)
            .mapToObj(i -> new Row(200_000 + i * 10_000L, "CAPTURED", "2026-05-1" + i + "T10:00:00Z"))
            .toList();

    static RuleHit rule(String code, String name) {
        return RuleHit.builder().code(code).name(name).appliedScore(25).build();
    }

    static CasePaymentsToolResponse.Payment casePayment(String id, long amount, Instant at) {
        return CasePaymentsToolResponse.Payment.builder()
                .paymentId(id)
                .amount(amount)
                .paymentStatus("HELD")
                .requestedAt(at)
                .build();
    }

    static FdsEvidenceToolResponse.Payment fds(String id, List<RuleHit> rules, String country) {
        return FdsEvidenceToolResponse.Payment.builder()
                .paymentId(id)
                .riskScore(50)
                .fdsResult("REVIEW")
                .ruleHits(rules)
                .signals(FdsEvidenceToolResponse.Signals.builder()
                        .cardProvided(true)
                        .deviceProvided(true)
                        .ipCountryCode(country)
                        .ipProvided(true)
                        .build())
                .build();
    }

    static PaymentHistoryToolResponse history(List<Row> rows) {
        List<PaymentHistoryToolResponse.Payment> payments = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            payments.add(PaymentHistoryToolResponse.Payment.builder()
                    .paymentId("h" + i)
                    .amount(row.amount())
                    .paymentStatus(row.status())
                    .requestedAt(Instant.parse(row.at()))
                    .build());
        }
        return PaymentHistoryToolResponse.builder()
                .caseId("1011")
                .evidenceVersion(1)
                .source("P5")
                .retrievedAt(AT)
                .lookbackDays(30)
                .payments(payments)
                .statusCounts(List.of())
                .truncated(false)
                .build();
    }

    static Match match(int others) {
        return Match.builder()
                .provided(true)
                .otherCustomerCount(others)
                .paymentCount(others == 0 ? 0 : 9)
                .resultCounts(List.of())
                .declinedCount(others == 0 ? 0 : 2)
                .build();
    }

    static final Match NOT_PROVIDED =
            Match.builder().provided(false).resultCounts(List.of()).build();

    // Spring 응답 모양 — 사건 결제가 "1" 하나라 결제별 칸(#533)은 사건 합계와 같다
    static LinkedPaymentsToolResponse linked(int cardOthers, int deviceOthers) {
        Match card = match(cardOthers);
        Match device = match(deviceOthers);
        return LinkedPaymentsToolResponse.builder()
                .caseId("1011")
                .evidenceVersion(1)
                .source("P2")
                .retrievedAt(AT)
                .lookbackDays(30)
                .card(card)
                .device(device)
                .ip(NOT_PROVIDED)
                .payments(List.of(new PaymentLinks("1", card, device, NOT_PROVIDED)))
                .build();
    }

    static Basis basis(
            long amount,
            List<RuleHit> rules,
            PaymentHistoryToolResponse history,
            LinkedPaymentsToolResponse linked,
            String country) {
        CasePaymentsToolResponse.Payment payment = casePayment("1", amount, AT);
        return DecisionBasisCalculator.basis(payment, fds("1", rules, country), List.of(payment), history, linked);
    }

    static Map<String, Signal> byLabel(Basis basis) {
        return basis.signals().stream().collect(Collectors.toMap(Signal::label, Function.identity()));
    }

    // 1011 — 같은 카드를 다른 고객이 씀(고위험) + 새 기기 · 새 카드 · 해외 접속(주의)
    // 매입 완료 이력이 있어도 고액 징후가 없어 아무것도 설명하지 못한다 → 승인 취소 검토
    @Test
    void basis_linkedCardWithNewCardAndOverseas_isRejectReview() {
        Basis basis = basis(
                205_600, List.of(rule("R02", "신규 단말기"), rule("R03", "신규 카드")), history(USUAL), linked(1, 0), "TH");

        assertThat(basis.rule()).isEqualTo("HIGH_RISK_WITH_OTHERS");
        assertThat(basis.recommendation()).isEqualTo("REJECT_REVIEW");
        assertThat(byLabel(basis).get("같은 카드를 다른 고객이 사용").level()).isEqualTo("HIGH_RISK");
        assertThat(byLabel(basis)).containsKey("해외 접속 (TH)");
        assertThat(basis.signals()).allMatch(s -> s.normalReason() == null);
        Normal similar = basis.normals().getFirst();
        assertThat(similar.canExplain()).containsExactly("고액 결제");
        assertThat(similar.explains()).isEmpty();
    }

    // 1066 — 카드 테스팅 모양: 거절 후 재시도(고위험) + 단시간 반복(주의), 승인 이력 없음
    // 단시간 반복은 거절 후 재시도와 같은 행동이라 따로 세지 않는다 → 추가 확인 (#501 리뷰)
    @Test
    void basis_retryWithRepeatWithoutApprovedHistory_countsOnce() {
        List<Row> declined = List.of(
                new Row(7_800, "DECLINED", "2026-05-20T03:51:47Z"),
                new Row(29_500, "DECLINED", "2026-04-20T10:17:36Z"));
        Basis basis = basis(
                4_600,
                List.of(rule("R04", "단시간 반복 결제"), rule("R08", "거절 후 재시도")),
                history(declined),
                linked(0, 0),
                "KR");

        assertThat(basis.rule()).isEqualTo("HIGH_RISK_ONLY");
        assertThat(basis.recommendation()).isEqualTo("NEEDS_CHECK");
        assertThat(byLabel(basis).get("단시간 반복 결제").countedIn()).isEqualTo("거절 후 재시도");
    }

    // 1015 — 같은 모양의 정상 고객: 거절 후 재시도만 센다 → 추가 확인 (정상 고객을 승인 취소 검토로 보내지 않게)
    @Test
    void basis_retryWithRepeatForUsualCustomer_isNeedsCheck() {
        List<Row> rows = List.of(
                new Row(32_000, "CAPTURED", "2026-06-30T10:28:20Z"),
                new Row(22_100, "CAPTURED", "2026-06-27T11:47:43Z"));
        Basis basis = basis(
                28_100, List.of(rule("R04", "단시간 반복 결제"), rule("R08", "거절 후 재시도")), history(rows), linked(0, 0), "KR");

        assertThat(basis.rule()).isEqualTo("HIGH_RISK_ONLY");
        Signal retry = byLabel(basis).get("거절 후 재시도");
        assertThat(retry.normalReason()).isNull();
        assertThat(retry.countedIn()).isNull();
    }

    // 승인 이력이 있고 새 카드 규칙이 안 걸려도 "본인 카드"로 보지 않는다 (#501 리뷰) — 단시간 반복만 남으면 추가 확인
    @Test
    void basis_missingNewCardRule_isNotOwnCardReason() {
        Basis basis = basis(
                28_000,
                List.of(rule("R04", "단시간 반복 결제")),
                history(List.of(new Row(30_000, "CAPTURED", "2026-05-01T10:00:00Z"))),
                linked(0, 0),
                "KR");

        assertThat(basis.rule()).isEqualTo("CAUTION_ONLY");
        assertThat(basis.signals().getFirst().normalReason()).isNull();
    }

    // 1014 — 해외 여행 중인 정상 고객: 새 기기 · 새 카드 · 해외 접속(주의 3개)만 → 추가 확인
    // 주의 징후 개수로 승인 취소 검토를 만들지 않는다 (#618 — 무게를 넣지 않은 이유)
    @Test
    void basis_threeCautionSignalsOnly_isNeedsCheck() {
        Basis basis = basis(
                150_000,
                List.of(rule("R02", "신규 단말기"), rule("R03", "신규 카드")),
                history(List.of(new Row(140_000, "CAPTURED", "2026-05-01T10:00:00Z"))),
                linked(0, 0),
                "VN");

        assertThat(basis.signals()).hasSize(3);
        assertThat(basis.rule()).isEqualTo("CAUTION_ONLY");
        assertThat(basis.recommendation()).isEqualTo("NEEDS_CHECK");
    }

    // 평소 금액의 고액 결제 — 비슷한 금액 매입 완료 2건 이상이 고액 징후를 설명한다 → 매입 진행 검토
    @Test
    void basis_highAmountExplainedByUsualAmount_isAcceptReview() {
        Basis basis = basis(220_000, List.of(rule("R01", "고액 결제")), history(USUAL), linked(0, 0), "KR");

        assertThat(basis.rule()).isEqualTo("ALL_EXPLAINED");
        assertThat(basis.recommendation()).isEqualTo("ACCEPT_REVIEW");
        assertThat(basis.signals().getFirst().normalReason()).isEqualTo("최근 30일 비슷한 금액의 결제 5건 매입 완료");
        assertThat(basis.normals().getFirst().explains()).containsExactly("고액 결제");
    }

    // 징후가 하나도 없으면 칸 값은 ALL_EXPLAINED(정상 사유 없는 징후 없음) · 매입 진행 검토 그대로 (#618)
    // 칸 값을 새로 만들면 화면 기준표 4칸 어디에도 ✓가 붙지 않고, 저장된 보고서와 값이 달라진다
    @Test
    void basis_noSignals_keepsAllExplainedAndAcceptReview() {
        Basis basis = basis(30_000, List.of(), history(List.of()), linked(0, 0), "KR");

        assertThat(basis.signals()).isEmpty();
        assertThat(basis.rule()).isEqualTo("ALL_EXPLAINED");
        assertThat(basis.recommendation()).isEqualTo("ACCEPT_REVIEW");
    }

    // 비슷한 금액 이력이 1건뿐이면 정상 사유로 보지 않는다
    @Test
    void basis_singleSimilarPayment_isNotEnough() {
        Basis basis = basis(220_000, List.of(rule("R01", "고액 결제")), history(USUAL.subList(0, 1)), linked(0, 0), "KR");

        assertThat(basis.rule()).isEqualTo("CAUTION_ONLY");
    }

    // 사건 결제 앞뒤 10분 안의 결제는 평소 결제가 아니다 — 같은 연속 결제로 고액을 설명하지 않는다
    @Test
    void basis_burstPayments_areNotUsualHistory() {
        List<Row> burst = List.of(
                new Row(220_000, "CAPTURED", "2026-05-21T11:10:00Z"),
                new Row(220_000, "CAPTURED", "2026-05-21T11:12:00Z"));
        Basis basis = basis(220_000, List.of(rule("R01", "고액 결제")), history(burst), linked(0, 0), "KR");

        assertThat(basis.rule()).isEqualTo("CAUTION_ONLY");
    }

    // 사건 뒤 결제도 평소 결제가 아니다 — 보고서를 늦게 만들어도 사건 뒤 결제로 고액을 설명하지 않는다 (#501 리뷰)
    @Test
    void basis_paymentsAfterCase_areNotUsualHistory() {
        List<Row> later = List.of(
                new Row(220_000, "CAPTURED", "2026-05-22T10:00:00Z"),
                new Row(220_000, "CAPTURED", "2026-05-23T10:00:00Z"));
        Basis basis = basis(220_000, List.of(rule("R01", "고액 결제")), history(later), linked(0, 0), "KR");

        assertThat(basis.rule()).isEqualTo("CAUTION_ONLY");
    }

    // 고위험 국가 규칙이 걸렸으면 해외 접속을 따로 세지 않는다 · 예전 영문 코드도 같은 징후로 읽는다
    @Test
    void basis_highRiskCountryAndLegacyCode_readAsSameSignals() {
        Basis basis = basis(100_000, List.of(rule("R05", "고위험 국가 IP"), rule("NEW_CARD", "처음 쓰는 카드")), null, null, "NG");

        assertThat(basis.signals()).extracting(Signal::key).containsExactly("R05", "R03");
        assertThat(basis.rule()).isEqualTo("CAUTION_ONLY");
    }

    // 징후 이름은 FDS 규칙 이름 그대로 — Case 상세 적중 규칙 · 조사 요약 밑줄과 같은 이름 (#505)
    @ParameterizedTest
    @CsvSource({
        "R01,고액 결제",
        "R02,신규 단말기",
        "R03,신규 카드",
        "R04,단시간 반복 결제",
        "R05,고위험 국가 IP",
        "R06,카드 테스팅",
        "R07,차단 목록",
        "R08,거절 후 재시도"
    })
    void basis_ruleSignalLabel_isFdsRuleName(String code, String name) {
        Basis basis = basis(100_000, List.of(rule(code, name)), null, null, "KR");

        assertThat(basis.signals()).extracting(Signal::label).containsExactly(name);
    }

    // 사건은 30분 안의 보류 결제를 계속 합친다 — 10:00 보류 → 10:14 정상 결제 2건 → 10:28 보류 (#532)
    // 10:14 결제는 사건이 시작된 뒤의 결제라 평소 결제가 아니다 — 두 사건 결제 모두 추가 확인
    @Test
    void basis_paymentsDuringStretchedCase_areNotUsualHistory() {
        List<CasePaymentsToolResponse.Payment> stretched = List.of(
                casePayment("1", 220_000, Instant.parse("2026-05-21T10:00:00Z")),
                casePayment("2", 220_000, Instant.parse("2026-05-21T10:28:00Z")));
        PaymentHistoryToolResponse during = history(List.of(
                new Row(220_000, "CAPTURED", "2026-05-21T10:14:00Z"),
                new Row(230_000, "CAPTURED", "2026-05-21T10:14:30Z")));

        for (CasePaymentsToolResponse.Payment payment : stretched) {
            Basis basis = DecisionBasisCalculator.basis(
                    payment,
                    fds(payment.paymentId(), List.of(rule("R01", "고액 결제")), "KR"),
                    stretched,
                    during,
                    linked(0, 0));
            assertThat(basis.normals()).isEmpty();
            assertThat(basis.recommendation()).isEqualTo("NEEDS_CHECK");
        }
    }

    static Basis errorBasis(LinkedPaymentsToolResponse linked) {
        CasePaymentsToolResponse.Payment payment = casePayment("1", 220_000, AT);
        FdsEvidenceToolResponse.Payment error = FdsEvidenceToolResponse.Payment.builder()
                .paymentId("1")
                .fdsResult("ERROR")
                .ruleHits(List.of())
                .build();
        return DecisionBasisCalculator.basis(payment, error, List.of(payment), history(USUAL), linked);
    }

    // FDS 점수 없음만 있으면 추가 확인 — "징후 없음 → 매입 진행 검토"가 되지 않는다 (#532)
    @Test
    void basis_fdsErrorAlone_isNeedsCheck() {
        Basis basis = errorBasis(linked(0, 0));

        assertThat(basis.recommendation()).isEqualTo("NEEDS_CHECK");
        assertThat(basis.signals()).extracting(Signal::label).contains("FDS 평가 결과 미확인");
    }

    // 점수 없음 + 고위험 하나 — 점수 없음을 "다른 징후"로 세어 승인 취소 검토로 올리지 않는다
    @Test
    void basis_fdsErrorWithSingleHighRisk_isNotRaised() {
        Basis basis = errorBasis(linked(1, 0));

        assertThat(basis.rule()).isEqualTo("HIGH_RISK_ONLY");
        assertThat(basis.recommendation()).isEqualTo("NEEDS_CHECK");
    }

    // 점수 없음 + 고위험 · 다른 징후가 겹치면 승인 취소 검토를 유지한다 — 내리지도 않는다
    @Test
    void basis_fdsErrorWithHighRiskAndOthers_keepsRejectReview() {
        Basis basis = errorBasis(linked(1, 1));

        assertThat(basis.recommendation()).isEqualTo("REJECT_REVIEW");
    }

    // 같은 카드 징후는 그 결제의 것만 붙인다 (#533) — 결제 1만 겹치고 결제 2는 깨끗하다
    // 결제별 칸이 없는 예전 응답이면 사건 단위로 붙이되 사실대로 "이 사건 결제 중"이라고 쓴다
    @Test
    void forCase_linkedCardOnlyOnMatchingPayment_orCaseLevelWithoutPerPayment() {
        List<CasePaymentsToolResponse.Payment> payments =
                List.of(casePayment("1", 50_000, AT), casePayment("2", 50_000, AT.plusSeconds(60)));
        CasePaymentsToolResponse cases = new CasePaymentsToolResponse("9", 1, "P3·P5", AT, payments, List.of());
        FdsEvidenceToolResponse fds = new FdsEvidenceToolResponse(
                "9", 1, "P2", AT, List.of(fds("1", List.of(), "KR"), fds("2", List.of(), "KR")), List.of());
        LinkedPaymentsToolResponse perPayment = LinkedPaymentsToolResponse.builder()
                .caseId("9")
                .evidenceVersion(1)
                .source("P2")
                .retrievedAt(AT)
                .lookbackDays(30)
                .card(match(1))
                .device(match(0))
                .ip(match(0))
                .payments(List.of(
                        new PaymentLinks("1", match(1), match(0), match(0)),
                        new PaymentLinks("2", match(0), match(0), match(0))))
                .build();
        LinkedPaymentsToolResponse caseLevel = LinkedPaymentsToolResponse.builder()
                .caseId("9")
                .evidenceVersion(1)
                .source("P2")
                .retrievedAt(AT)
                .lookbackDays(30)
                .card(match(1))
                .device(match(0))
                .ip(match(0))
                .build();

        DecisionBasisToolResponse own = DecisionBasisCalculator.forCase(cases, fds, null, perPayment, AT);
        DecisionBasisToolResponse old = DecisionBasisCalculator.forCase(cases, fds, null, caseLevel, AT);

        assertThat(own.payments().get(0).basis().signals())
                .extracting(Signal::label)
                .containsExactly("같은 카드를 다른 고객이 사용");
        assertThat(own.payments().get(1).basis().signals()).isEmpty();
        assertThat(old.payments().get(1).basis().signals())
                .extracting(Signal::label)
                .containsExactly("이 사건 결제 중 같은 카드를 다른 고객이 사용");
    }

    // 금액 · 요청 시각을 모르거나 FDS 근거가 없는 결제는 기준표에서 뺀다 — 받는 쪽이 추가 확인으로 둔다
    @Test
    void forCase_paymentWithoutAmountOrEvidence_isLeftOut() {
        List<CasePaymentsToolResponse.Payment> payments = List.of(
                casePayment("1", 50_000, AT),
                CasePaymentsToolResponse.Payment.builder().paymentId("2").build(),
                casePayment("3", 50_000, AT));
        CasePaymentsToolResponse cases = new CasePaymentsToolResponse("9", 4, "P3·P5", AT, payments, List.of("2"));
        FdsEvidenceToolResponse fds = new FdsEvidenceToolResponse(
                "9", 4, "P2", AT, List.of(fds("1", List.of(), "KR"), fds("2", List.of(), "KR")), List.of());

        DecisionBasisToolResponse response = DecisionBasisCalculator.forCase(cases, fds, null, linked(0, 0), AT);

        assertThat(response.evidenceVersion()).isEqualTo(4);
        assertThat(response.payments())
                .extracting(DecisionBasisToolResponse.PaymentBasis::paymentId)
                .containsExactly("1");
    }

    // 모든 징후에 판정 전에 확인할 것 문장이 있다 (#591) — 새 징후를 더하면 문장도 더해야 한다
    @Test
    void checkBefore_everySignalKey_hasSentence() {
        Basis basis = DecisionBasisCalculator.basis(
                casePayment("1", 100_000, AT),
                FdsEvidenceToolResponse.Payment.builder()
                        .paymentId("1")
                        .fdsResult("ERROR")
                        .ruleHits(List.of("R01", "R02", "R03", "R04", "R06", "R07", "R08").stream()
                                .map(code -> rule(code, code))
                                .toList())
                        .signals(FdsEvidenceToolResponse.Signals.builder()
                                .ipCountryCode("US")
                                .build())
                        .build(),
                List.of(casePayment("1", 100_000, AT)),
                null,
                LinkedPaymentsToolResponse.builder()
                        .card(match(1))
                        .device(match(1))
                        .ip(match(1))
                        .build());
        Basis highRiskCountry = basis(100_000, List.of(rule("R05", "고위험 국가 IP")), null, null, "NG");

        assertThat(basis.signals())
                .extracting(Signal::key)
                .containsExactlyInAnyOrder(
                        "R01",
                        "R02",
                        "R03",
                        "R04",
                        "R06",
                        "R07",
                        "R08",
                        "FDS_UNCONFIRMED",
                        "OVERSEAS",
                        "LINKED_CARD",
                        "LINKED_DEVICE",
                        "LINKED_IP");
        assertThat(basis.signals())
                .allMatch(s -> s.checkBefore() != null && !s.checkBefore().isBlank());
        assertThat(highRiskCountry.signals().getFirst().checkBefore()).isNotBlank();
    }

    // 종합 권고 (ADR-0009 결정 4) — 하나라도 승인 취소 검토면 승인 취소 검토, 모두 매입 진행 검토일 때만 매입 진행 검토
    @Test
    void overall_mixedRecommendations_followsDecision4() {
        assertThat(DecisionBasisCalculator.overall(List.of("ACCEPT_REVIEW", "REJECT_REVIEW")))
                .isEqualTo("REJECT_REVIEW");
        assertThat(DecisionBasisCalculator.overall(List.of("ACCEPT_REVIEW", "NEEDS_CHECK")))
                .isEqualTo("NEEDS_CHECK");
        assertThat(DecisionBasisCalculator.overall(List.of("ACCEPT_REVIEW", "ACCEPT_REVIEW")))
                .isEqualTo("ACCEPT_REVIEW");
        assertThat(DecisionBasisCalculator.overall(List.of())).isEqualTo("NEEDS_CHECK");
    }
}
