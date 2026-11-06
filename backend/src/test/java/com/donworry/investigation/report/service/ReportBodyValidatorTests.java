package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.repository.CasePaymentRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReportBodyValidatorTests {

    static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    @Mock
    CasePaymentRepository casePaymentRepository;

    // 결론이 없거나 종합 권고 값이 허용 밖이면 저장하지 않는다
    @Test
    void validate_missingVerdictOrUnknownOverallRecommendation_returnsErrors() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        ReportBodyValidator validator = new ReportBodyValidator(casePaymentRepository);
        AiReport report = AiReport.pending(7L, 1, 1, NOW);
        Map<String, Object> noVerdict = new HashMap<>(body());
        noVerdict.remove("verdict");
        Map<String, Object> nullVerdict = new HashMap<>(body());
        nullVerdict.put("verdict", null);
        Map<String, Object> unknownRecommendation = new HashMap<>(body());
        unknownRecommendation.put(
                "verdict", Map.of("headline", "본인 확인이 필요합니다.", "overallRecommendation", "AUTO_ACCEPT"));

        assertThat(validator.validate(report, body())).isEmpty();
        assertThat(validator.validate(report, noVerdict)).anyMatch(error -> error.contains("verdict"));
        assertThat(validator.validate(report, nullVerdict)).anyMatch(error -> error.contains("verdict"));
        assertThat(validator.validate(report, unknownRecommendation)).anyMatch(error -> error.contains("AUTO_ACCEPT"));
    }

    // 새 형식에서 요약 근거 번호(summaryFacts)가 빠지면 저장하지 않는다 (#559)
    // ai-server와 Spring의 보고서 계약이 어긋난 본문이 성공 보고서로 남지 않게
    @Test
    void validate_missingSummaryFacts_returnsError() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        ReportBodyValidator validator = new ReportBodyValidator(casePaymentRepository);
        Map<String, Object> noSummaryFacts = new HashMap<>(body());
        noSummaryFacts.remove("summaryFacts");

        assertThat(validator.validate(AiReport.pending(7L, 1, 1, NOW), noSummaryFacts))
                .anyMatch(error -> error.contains("summaryFacts"));
    }

    // 새 선택 칸(개요 · 안전망 · 조사 범위 · 확인할 것 · 30일 표시)이 든 보고서도 저장한다 (#591)
    // ai-server만 바꿔 Spring이 성공 보고서를 거부하면 보고서가 사라진다
    @Test
    void validate_bodyWithNewOptionalFields_returnsNoErrors() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        ReportBodyValidator validator = new ReportBodyValidator(casePaymentRepository);
        Map<String, Object> withNewFields = new HashMap<>(body());
        withNewFields.put("caseOverview", "고객 customer-1의 결제 1건이 고액 결제 규칙에 걸려 매입 보류된 사건입니다.");
        withNewFields.put("summaryAppended", List.of("연결-1"));
        withNewFields.put(
                "scope", Map.of("checked", List.of("사건 결제 목록"), "withheld", List.of(), "unavailable", List.of()));
        withNewFields.put(
                "paymentAnalyses",
                List.of(Map.of(
                        "paymentId",
                        "3",
                        "recommendation",
                        "NEEDS_CHECK",
                        "ruleHitDetails",
                        List.of(),
                        "basis",
                        Map.of(
                                "signals",
                                List.of(Map.of("key", "R01", "checkBefore", "구매 목적 확인")),
                                "rule",
                                "CAUTION_ONLY",
                                "recommendation",
                                "NEEDS_CHECK"))));

        assertThat(validator.validate(AiReport.pending(7L, 1, 1, NOW), withNewFields))
                .isEmpty();
    }

    // 결제별 권고가 기준표 칸의 권고와 다르면 저장하지 않는다 (#618)
    // 기준표는 Spring이 계산한다 — 보고서에서 권고만 다른 값으로 바뀌어 직원 화면에 나가면 판정 근거와 권고가 어긋난다
    // 판정 근거가 없는 결제는 추가 확인만 허용한다 — 근거 없이 승인 취소 · 매입 진행 쪽 권고가 나가지 않게
    @Test
    void validate_recommendationNotMatchingBasisRule_returnsErrors() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        ReportBodyValidator validator = new ReportBodyValidator(casePaymentRepository);
        AiReport report = AiReport.pending(7L, 1, 1, NOW);

        assertThat(validator.validate(report, withAnalysis("ACCEPT_REVIEW", basis("CAUTION_ONLY", "NEEDS_CHECK"))))
                .anyMatch(error -> error.contains("CAUTION_ONLY"));
        assertThat(validator.validate(report, withAnalysis("REJECT_REVIEW", null)))
                .anyMatch(error -> error.contains("판정 근거 없이"));
        assertThat(validator.validate(report, withAnalysis("NEEDS_CHECK", basis("AUTO", "NEEDS_CHECK"))))
                .anyMatch(error -> error.contains("허용 밖"));
        assertThat(validator.validate(
                        report, withAnalysis("REJECT_REVIEW", basis("HIGH_RISK_WITH_OTHERS", "REJECT_REVIEW"))))
                .noneMatch(error -> error.contains("결제 3"));
    }

    // 종합 권고가 결제별 권고로 정한 값과 다르면 저장하지 않는다 (ADR-0009 결정 4, #618)
    @Test
    void validate_overallNotMatchingPaymentRecommendations_returnsError() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        ReportBodyValidator validator = new ReportBodyValidator(casePaymentRepository);
        Map<String, Object> body =
                new HashMap<>(withAnalysis("REJECT_REVIEW", basis("HIGH_RISK_WITH_OTHERS", "REJECT_REVIEW")));

        assertThat(validator.validate(AiReport.pending(7L, 1, 1, NOW), body))
                .containsExactly("종합 권고(NEEDS_CHECK)가 결제별 권고로 정한 값(REJECT_REVIEW)과 다르다");
    }

    private static Map<String, Object> basis(String rule, String recommendation) {
        return Map.of("signals", List.of(), "normals", List.of(), "rule", rule, "recommendation", recommendation);
    }

    private static Map<String, Object> withAnalysis(String recommendation, Map<String, Object> basis) {
        Map<String, Object> analysis =
                new HashMap<>(Map.of("paymentId", "3", "recommendation", recommendation, "ruleHitDetails", List.of()));
        analysis.put("basis", basis);
        Map<String, Object> body = new HashMap<>(body());
        body.put("paymentAnalyses", List.of(analysis));
        return body;
    }

    private static Map<String, Object> body() {
        return Map.ofEntries(
                Map.entry("overview", Map.of("caseId", "7")),
                Map.entry("summary", List.of("요약")),
                Map.entry(
                        "paymentAnalyses",
                        List.of(Map.of(
                                "paymentId", "3", "recommendation", "NEEDS_CHECK", "ruleHitDetails", List.of()))),
                Map.entry("timeline", List.of()),
                Map.entry("summaryFacts", List.of()),
                Map.entry("facts", List.of()),
                Map.entry("additionalChecks", List.of()),
                Map.entry("recommendation", Map.of("summary", "추가 확인", "limitations", List.of())),
                Map.entry("evidence", List.of()),
                Map.entry("verdict", Map.of("headline", "본인 확인이 필요합니다.", "overallRecommendation", "NEEDS_CHECK")));
    }
}
