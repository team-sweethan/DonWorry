package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse.Signals;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.Match;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.Nearby;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.ResultCount;
import com.donworry.investigation.report.dto.OrderContextToolResponse;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse.StatusCount;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse.SharedRule;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse.SimilarCase;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FixedReportGeneratorTests {

    static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    @Mock
    SecurityCaseRepository securityCaseRepository;

    @Mock
    CasePaymentRepository casePaymentRepository;

    @Mock
    PaymentInfoReader paymentInfoReader;

    @Mock
    SimilarCaseFinder similarCaseFinder;

    @Mock
    PaymentHistoryFinder paymentHistoryFinder;

    @Mock
    PaymentSignalReader paymentSignalReader;

    @Mock
    LinkedPaymentFinder linkedPaymentFinder;

    @Mock
    OrderContextFinder orderContextFinder;

    // 긴급정지 중에 만든 고정 보고서는 조사 과정에 그 이유와 한계 문장을 남긴다 — "AI 연결 전"으로 오해하지 않게 (ADR-0083 결정 6)
    @Test
    @SuppressWarnings("unchecked")
    void generate_whenStoppedByAdmin_marksFixedReasonInTrace() {
        FixedReportGenerator generator = generatorForCase7();

        Map<String, Object> body = generator
                .generate(7L, 1, 1, NOW, FixedReportReason.ADMIN_STOPPED)
                .orElseThrow();

        assertThat((Map<String, Object>) body.get("investigationTrace"))
                .containsEntry("provider", "FIXED")
                .containsEntry("fixedReason", "ADMIN_STOPPED");
        assertThat((List<String>) ((Map<String, Object>) body.get("recommendation")).get("limitations"))
                .containsExactly(FixedReportReason.ADMIN_STOPPED.limitation);
    }

    // 고정 보고서 개요 한 줄은 ai-server와 같은 문장이다 — ai-server test_case_overview_multiple_payments_matches_tool_values와 같은 입력 ·
    // 기대값
    @Test
    void generate_caseOverview_matchesAiServerSentence() {
        List<PaymentInfo> payments = List.of(
                new PaymentInfo(
                        15L,
                        600_000L,
                        "HELD",
                        Instant.parse("2026-10-27T12:00:00Z"),
                        60,
                        "REVIEW",
                        List.of(new RuleHit("R01", "고액 결제", 30), new RuleHit("R02", "처음 쓰는 카드", 20))),
                new PaymentInfo(
                        16L,
                        170_000L,
                        "HELD",
                        Instant.parse("2026-10-27T12:07:00Z"),
                        45,
                        "REVIEW",
                        List.of(new RuleHit("R03", "단시간 반복 결제", 25), new RuleHit("R01", "고액 결제", 30))));

        assertThat(FixedReportGenerator.caseOverview("customer-1", payments))
                .isEqualTo("고객 customer-1의 결제 2건(합계 770,000원, 10/27 21:00~21:07)이 "
                        + "고액 결제 · 처음 쓰는 카드 · 단시간 반복 결제 규칙에 걸려 매입 보류된 사건입니다.");
    }

    // 고정 문장 보고서는 확인하지 않은 정상 근거를 넣지 않고 새 칸(결론 · 이유 · 규칙 근거)을 채운다
    @Test
    void generate_withEvidence_omitsUncheckedNormalEvidenceAndFillsNewKeys() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        when(paymentInfoReader.findAll(List.of(3L)))
                .thenReturn(Map.of(
                        3L,
                        new PaymentInfo(
                                3L, 600_000L, "HELD", NOW, 60, "REVIEW", List.of(new RuleHit("R01", "고액 결제", 30)))));
        FixedReportGenerator generator = new FixedReportGenerator(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        Map<?, ?> analysis = (Map<?, ?>) ((List<?>) body.get("paymentAnalyses")).getFirst();
        assertThat(analysis.containsKey("riskEvidence") || analysis.containsKey("normalEvidence"))
                .isFalse();
        assertThat((List<?>) body.get("summaryFacts")).isEmpty();
        assertThat(analysis.get("reason")).isInstanceOf(String.class);
        // 기준값 · 실제값이 없는 규칙은 criterion · observed가 null — AI 보고서와 같은 모양 (#326)
        Map<String, Object> expectedRule =
                new HashMap<>(Map.of("code", "R01", "name", "고액 결제", "appliedScore", 30, "evidenceId", "E-7-v1-02"));
        expectedRule.put("criterion", null);
        expectedRule.put("observed", null);
        assertThat(analysis.get("ruleHitDetails")).isEqualTo(List.of(expectedRule));
        assertThat(new ReportBodyValidator(casePaymentRepository).validate(AiReport.pending(7L, 1, 1, NOW), body))
                .isEmpty();
    }

    // AI 서버를 끈 시연에서도 경위가 ai-server와 같은 모양 — 결제 · 매입 보류 → 사건 생성, 같은 시각이면 결제 먼저 (#310)
    // AI가 조사하지 않았으므로 "AI 조사 시작"은 남기지 않는다 (#310 리뷰)
    @Test
    void generate_timeline_hasPaymentHoldAndCaseCreated() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 15L, NOW)));
        when(paymentInfoReader.findAll(List.of(15L)))
                .thenReturn(Map.of(
                        15L,
                        new PaymentInfo(
                                15L, 600_000L, "HELD", NOW, 60, "REVIEW", List.of(new RuleHit("R01", "고액 결제", 30)))));
        FixedReportGenerator generator = new FixedReportGenerator(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        assertThat(body.get("timeline"))
                .isEqualTo(List.of(
                        Map.of(
                                "at",
                                NOW.toString(),
                                "event",
                                "결제 15 · 600,000원 → FDS 검토 60점 → 매입 보류",
                                "kind",
                                "PAYMENT"),
                        Map.of("at", NOW.toString(), "event", "사건 자동 생성", "kind", "CASE")));
        assertThat(body.get("timeline").toString()).doesNotContain("AI 조사");
    }

    // AI 서버를 끈 시연의 보고서는 "고정 문장"으로 표시되고, AI가 한 단계는 하나도 없다 (#294)
    @Test
    void generate_aiDisabled_addsFixedProviderTrace() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        when(paymentInfoReader.findAll(List.of(3L)))
                .thenReturn(Map.of(
                        3L,
                        new PaymentInfo(
                                3L, 600_000L, "HELD", NOW, 60, "REVIEW", List.of(new RuleHit("R01", "고액 결제", 30)))));
        FixedReportGenerator generator = new FixedReportGenerator(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        Map<?, ?> trace = (Map<?, ?>) body.get("investigationTrace");
        assertThat(trace.get("provider")).isEqualTo("FIXED");
        List<?> steps = (List<?>) trace.get("steps");
        assertThat(steps).isNotEmpty();
        assertThat(steps)
                .allSatisfy(step -> assertThat(((Map<?, ?>) step).get("actor")).isEqualTo("SYSTEM"));
        assertThat(trace.toString()).doesNotContain("600000");
    }

    // AI 서버를 끈 시연에서도 비슷한 과거 사건 카드가 채워진다 — 값은 SimilarCaseFinder 결과 그대로 (#296)
    @Test
    void generate_aiDisabled_addsSimilarCases() {
        FixedReportGenerator generator = generatorForCase7();
        when(similarCaseFinder.find(7L))
                .thenReturn(new SimilarCasesToolResponse(
                        "7",
                        1,
                        "P3·P2",
                        NOW.plusSeconds(5),
                        List.of(new SimilarCase(
                                "3", "SAME_CUSTOMER", List.of(new SharedRule("R01", "고액 결제")), NOW, 1, 1)),
                        4));

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        Map<?, ?> similar = (Map<?, ?>) body.get("similarCases");
        assertThat(similar.get("totalMatched")).isEqualTo(4);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) similar.get("cases")).getFirst();
        assertThat(first.get("caseId")).isEqualTo("3");
        assertThat(first.get("acceptCount")).isEqualTo(1);
        assertThat(first.get("rejectCount")).isEqualTo(1);
        // 증거 추적 줄은 실제 조회 응답의 출처 · 조회 시각 · 증거 버전을 쓴다 (#296 리뷰)
        assertThat(evidenceOf(body, "find_similar_cases")).singleElement().satisfies(e -> {
            assertThat(e.get("source")).isEqualTo("P3·P2");
            assertThat(e.get("retrievedAt")).isEqualTo(NOW.plusSeconds(5).toString());
            assertThat(e.get("dataVersion")).isEqualTo("evidence v1");
        });
    }

    // 비슷한 사건은 참고 자료라 조회에 실패해도 보고서는 만들어지고, 값만 비어 있다 (#296)
    @Test
    void generate_similarCasesLookupFails_stillBuildsReportWithNull() {
        FixedReportGenerator generator = generatorForCase7();
        when(similarCaseFinder.find(7L)).thenThrow(new IllegalStateException("P2 조회 실패"));

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        assertThat(body).containsKey("similarCases");
        assertThat(body.get("similarCases")).isNull();
        assertThat(body.get("verdict")).isNotNull();
        // 조회하지 못한 자료는 증거 추적 표에 조회한 것처럼 남기지 않는다 (#296 리뷰)
        assertThat(evidenceOf(body, "find_similar_cases")).isEmpty();
        assertThat(evidenceOf(body, "get_fds_evidence")).hasSize(1);
    }

    // AI 서버를 끈 시연에서도 고객 최근 결제 카드가 채워진다 — 값은 PaymentHistoryFinder 결과 그대로 (#338)
    @Test
    void generate_aiDisabled_addsPaymentHistory() {
        FixedReportGenerator generator = generatorForCase7();
        when(paymentHistoryFinder.find(7L, NOW, 30))
                .thenReturn(PaymentHistoryToolResponse.builder()
                        .caseId("7")
                        .evidenceVersion(1)
                        .source("P5")
                        .retrievedAt(NOW)
                        .lookbackDays(30)
                        .payments(List.of(PaymentHistoryToolResponse.Payment.builder()
                                .paymentId("2")
                                .amount(580_000L)
                                .paymentStatus("CAPTURED")
                                .requestedAt(NOW.minusSeconds(86_400))
                                .build()))
                        .statusCounts(List.of(new StatusCount("CAPTURED", 1)))
                        .truncated(false)
                        .build());

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        Map<?, ?> history = (Map<?, ?>) body.get("paymentHistory");
        assertThat(history.get("lookbackDays")).isEqualTo(30);
        assertThat(history.get("truncated")).isEqualTo(false);
        assertThat(history.get("statusCounts")).isEqualTo(List.of(Map.of("paymentStatus", "CAPTURED", "count", 1)));
        Map<?, ?> first = (Map<?, ?>) ((List<?>) history.get("payments")).getFirst();
        assertThat(first.get("paymentId")).isEqualTo("2");
        assertThat(first.get("amount")).isEqualTo(580_000L);
        assertThat(first.get("requestedAt")).isEqualTo(NOW.minusSeconds(86_400).toString());
        assertThat(evidenceOf(body, "get_payment_history")).singleElement().satisfies(e -> {
            assertThat(e.get("source")).isEqualTo("P5");
            assertThat(e.get("dataVersion")).isEqualTo("evidence v1");
        });
    }

    // 고객 최근 결제는 참고 자료라 조회에 실패해도 보고서는 만들어지고, 값만 비어 있다 (#338)
    @Test
    void generate_paymentHistoryLookupFails_stillBuildsReportWithNull() {
        FixedReportGenerator generator = generatorForCase7();
        when(paymentHistoryFinder.find(7L, NOW, 30)).thenThrow(new IllegalStateException("P5 조회 실패"));

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        assertThat(body).containsKey("paymentHistory");
        assertThat(body.get("paymentHistory")).isNull();
        assertThat(body.get("verdict")).isNotNull();
        assertThat(evidenceOf(body, "get_payment_history")).isEmpty();
    }

    // AI 서버를 끈 시연에서도 결제별 카드 · 기기 · 접속 국가가 채워진다 — 값은 PaymentSignalReader 결과 그대로 (#346)
    @Test
    void generate_aiDisabled_addsPaymentSignals() {
        FixedReportGenerator generator = generatorForCase7();
        when(paymentSignalReader.findAll(List.of(3L)))
                .thenReturn(Map.of(3L, new Signals("신한 ****1234", true, true, null, false)));

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        Map<?, ?> analysis = (Map<?, ?>) ((List<?>) body.get("paymentAnalyses")).getFirst();
        Map<String, Object> expected = new HashMap<>();
        expected.put("cardDisplayLabel", "신한 ****1234");
        expected.put("cardProvided", true);
        expected.put("deviceProvided", true);
        expected.put("ipCountryCode", null);
        expected.put("ipProvided", false);
        assertThat(analysis.get("signals")).isEqualTo(expected);
    }

    // AI 서버를 끈 시연에서도 같은 카드 · 기기 · IP 결제 카드가 채워진다 — 값은 LinkedPaymentFinder 결과 그대로 (#352)
    @Test
    void generate_aiDisabled_addsLinkedPayments() {
        FixedReportGenerator generator = generatorForCase7();
        Match cardMatch = Match.builder()
                .provided(true)
                .otherCustomerCount(2)
                .paymentCount(3)
                .resultCounts(List.of(new ResultCount("REVIEW", 2), new ResultCount("DENY", 1)))
                .declinedCount(1)
                .nearby(Nearby.builder()
                        .otherCustomerCount(1)
                        .paymentCount(1)
                        .resultCounts(List.of(new ResultCount("DENY", 1)))
                        .declinedCount(0)
                        .build())
                .build();
        Match noOther = Match.builder()
                .provided(true)
                .otherCustomerCount(0)
                .paymentCount(0)
                .resultCounts(List.of())
                .declinedCount(0)
                .build();
        Match notProvided = Match.builder()
                .provided(false)
                .otherCustomerCount(0)
                .paymentCount(0)
                .resultCounts(List.of())
                .declinedCount(0)
                .build();
        when(linkedPaymentFinder.find(7L, NOW))
                .thenReturn(LinkedPaymentsToolResponse.builder()
                        .caseId("7")
                        .evidenceVersion(1)
                        .source("P2")
                        .retrievedAt(NOW)
                        .lookbackDays(30)
                        .nearbyWindowMinutes(60)
                        .card(cardMatch)
                        .device(noOther)
                        .ip(notProvided)
                        .build());

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        Map<?, ?> linked = (Map<?, ?>) body.get("linkedPayments");
        assertThat(linked.get("lookbackDays")).isEqualTo(30);
        // 사건 결제 앞뒤 1시간 숫자도 ai-server와 같은 키로 옮긴다 (#637)
        assertThat(linked.get("nearbyWindowMinutes")).isEqualTo(60);
        assertThat(linked.get("card"))
                .isEqualTo(Map.of(
                        "provided",
                        true,
                        "otherCustomerCount",
                        2,
                        "paymentCount",
                        3,
                        "resultCounts",
                        List.of(Map.of("result", "REVIEW", "count", 2), Map.of("result", "DENY", "count", 1)),
                        "declinedCount",
                        1,
                        "nearby",
                        Map.of(
                                "otherCustomerCount", 1,
                                "paymentCount", 1,
                                "resultCounts", List.of(Map.of("result", "DENY", "count", 1)),
                                "declinedCount", 0)));
        assertThat(((Map<?, ?>) linked.get("ip")).get("provided")).isEqualTo(false);
        assertThat(evidenceOf(body, "find_linked_payments")).singleElement().satisfies(e -> {
            assertThat(e.get("source")).isEqualTo("P2");
            assertThat(e.get("dataVersion")).isEqualTo("evidence v1");
        });
    }

    // 같은 카드 · 기기 · IP 결제는 참고 자료라 조회에 실패해도 보고서는 만들어지고, 값만 비어 있다 (#352)
    @Test
    void generate_linkedLookupFails_reportStillSucceeds() {
        FixedReportGenerator generator = generatorForCase7();
        when(linkedPaymentFinder.find(7L, NOW)).thenThrow(new IllegalStateException("P2 조회 실패"));

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        assertThat(body).containsKey("linkedPayments");
        assertThat(body.get("linkedPayments")).isNull();
        assertThat(body.get("verdict")).isNotNull();
        assertThat(evidenceOf(body, "find_linked_payments")).isEmpty();
    }

    // 고정 보고서의 주문 정보는 AI 도구와 같은 계산으로 만든다 (#634)
    @Test
    void generate_withOrderContext_matchesAiToolCalculation() {
        FixedReportGenerator generator = generatorForCase7();
        when(orderContextFinder.find(7L, NOW))
                .thenReturn(OrderContextToolResponse.builder()
                        .caseId("7")
                        .evidenceVersion(1)
                        .source("P5")
                        .retrievedAt(NOW)
                        .payments(List.of(OrderContextToolResponse.Payment.builder()
                                .paymentId("3")
                                .provided(true)
                                .orderRef("order-3")
                                .orderName("딸기 케이크")
                                .orderGroup(1)
                                .orderToPaymentSeconds(42L)
                                .ordererProvided(true)
                                .ordererPhoneGroup(1)
                                .pickupDiffers(true)
                                .build()))
                        .build());

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        // AI 보고서(ai-server)가 Tool 응답을 그대로 옮기는 것과 같은 키 · 같은 값
        Map<?, ?> order = (Map<?, ?>) body.get("orderContext");
        Map<String, Object> expected = new HashMap<>();
        expected.put("paymentId", "3");
        expected.put("provided", true);
        expected.put("orderRef", "order-3");
        expected.put("orderName", "딸기 케이크");
        expected.put("orderGroup", 1);
        expected.put("orderToPaymentSeconds", 42L);
        expected.put("ordererProvided", true);
        expected.put("ordererPhoneGroup", 1);
        expected.put("pickupDiffers", true);
        assertThat(order.get("payments")).isEqualTo(List.of(expected));
        assertThat(evidenceOf(body, "get_order_context")).singleElement().satisfies(e -> {
            assertThat(e.get("source")).isEqualTo("P5");
            assertThat(e.get("dataVersion")).isEqualTo("evidence v1");
        });
    }

    private static List<Map<?, ?>> evidenceOf(Map<String, Object> body, String toolName) {
        List<Map<?, ?>> found = new ArrayList<>();
        for (Object item : (List<?>) body.get("evidence")) {
            if (item instanceof Map<?, ?> evidence && toolName.equals(evidence.get("toolName"))) {
                found.add(evidence);
            }
        }
        return found;
    }

    private FixedReportGenerator generatorForCase7() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        when(paymentInfoReader.findAll(List.of(3L)))
                .thenReturn(Map.of(
                        3L,
                        new PaymentInfo(
                                3L, 600_000L, "HELD", NOW, 60, "REVIEW", List.of(new RuleHit("R01", "고액 결제", 30)))));
        return new FixedReportGenerator(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);
    }

    // AI 서버가 꺼져도 고정 보고서가 판정 기준표로 권고한다 (#618) — 전에는 늘 추가 확인이었다
    // 카드 테스팅(고위험) + 고액 · 신규 단말기(주의) → 승인 취소 검토, 종합 권고 · 판정 근거도 같은 값
    @Test
    void generate_aiOff_recommendsByDecisionBasis() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        when(paymentInfoReader.findAll(List.of(3L)))
                .thenReturn(Map.of(
                        3L,
                        new PaymentInfo(
                                3L,
                                600_000L,
                                "HELD",
                                NOW,
                                90,
                                "REVIEW",
                                List.of(
                                        new RuleHit("R06", "카드 테스팅", 40),
                                        new RuleHit("R01", "고액 결제", 30),
                                        new RuleHit("R02", "신규 단말기", 20)))));
        // 실제 조회는 연결 결제가 없어도 null이 아니라 "없음" 응답을 준다 — null은 조회 실패다
        when(linkedPaymentFinder.find(7L, NOW)).thenReturn(noLinks(NOW));
        FixedReportGenerator generator = new FixedReportGenerator(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        Map<?, ?> analysis = (Map<?, ?>) ((List<?>) body.get("paymentAnalyses")).getFirst();
        DecisionBasisToolResponse.Basis basis = (DecisionBasisToolResponse.Basis) analysis.get("basis");
        assertThat(basis.rule()).isEqualTo("HIGH_RISK_WITH_OTHERS");
        assertThat(analysis.get("recommendation")).isEqualTo("REJECT_REVIEW");
        assertThat((String) analysis.get("reason")).startsWith("고위험 징후를 포함해 정상 사유가 없는 징후가 겹칩니다");
        assertThat(((Map<?, ?>) body.get("verdict")).get("overallRecommendation"))
                .isEqualTo("REJECT_REVIEW");
    }

    // 같은 카드 · 기기 · IP 조회가 실패하면 기준표 없이 추가 확인으로 둔다 (#618 Codex 리뷰)
    // "연결 징후 없음"으로 계산하면 고액 + 평소 금액 이력 결제가 매입 진행 검토로 나온다 — 고위험 징후를 확인하지 못했는데도
    @Test
    void generate_linkedLookupFails_needsCheckWithoutBasis() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        when(paymentInfoReader.findAll(List.of(3L)))
                .thenReturn(Map.of(
                        3L,
                        new PaymentInfo(
                                3L, 600_000L, "HELD", NOW, 60, "REVIEW", List.of(new RuleHit("R01", "고액 결제", 30)))));
        when(paymentHistoryFinder.find(7L, NOW, PaymentHistoryFinder.DEFAULT_LOOKBACK_DAYS))
                .thenReturn(usualHistory(NOW));
        when(linkedPaymentFinder.find(7L, NOW)).thenThrow(new IllegalStateException("조회 실패"));
        FixedReportGenerator generator = new FixedReportGenerator(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);

        Map<String, Object> body =
                generator.generate(7L, 1, 1, NOW, FixedReportReason.CONFIG_OFF).orElseThrow();

        Map<?, ?> analysis = (Map<?, ?>) ((List<?>) body.get("paymentAnalyses")).getFirst();
        assertThat(analysis.get("basis")).isNull();
        assertThat(analysis.get("recommendation")).isEqualTo("NEEDS_CHECK");
        assertThat(((Map<?, ?>) body.get("verdict")).get("overallRecommendation"))
                .isEqualTo("NEEDS_CHECK");
    }

    // 사건 며칠 전 비슷한 금액 매입 완료 2건 — 기준표가 고액 결제의 정상 사유로 본다
    static PaymentHistoryToolResponse usualHistory(Instant now) {
        return PaymentHistoryToolResponse.builder()
                .caseId("7")
                .evidenceVersion(1)
                .source("P5")
                .retrievedAt(now)
                .lookbackDays(PaymentHistoryFinder.DEFAULT_LOOKBACK_DAYS)
                .payments(List.of(
                        PaymentHistoryToolResponse.Payment.builder()
                                .paymentId("90")
                                .amount(580_000L)
                                .paymentStatus("CAPTURED")
                                .requestedAt(now.minusSeconds(3 * 86_400))
                                .build(),
                        PaymentHistoryToolResponse.Payment.builder()
                                .paymentId("91")
                                .amount(620_000L)
                                .paymentStatus("CAPTURED")
                                .requestedAt(now.minusSeconds(5 * 86_400))
                                .build()))
                .statusCounts(List.of(new StatusCount("CAPTURED", 2)))
                .truncated(false)
                .build();
    }

    static LinkedPaymentsToolResponse noLinks(Instant now) {
        Match none = Match.builder().provided(true).resultCounts(List.of()).build();
        return LinkedPaymentsToolResponse.builder()
                .caseId("7")
                .evidenceVersion(1)
                .source("P2")
                .retrievedAt(now)
                .lookbackDays(LinkedPaymentFinder.LOOKBACK_DAYS)
                .card(none)
                .device(none)
                .ip(none)
                .payments(List.of())
                .build();
    }
}
