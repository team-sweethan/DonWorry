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
