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

    // 판정 권고(결제별 권고 · 기준표 칸 · 결론 · 권고 요약)가 없는 새 보고서를 저장한다 (#644, ADR-0009 결정 13)
    // 새 ai-server는 이 키를 만들지 않는다 — 필수로 보면 AI 보고서 저장이 모두 실패한다
    @Test
    void validate_whenBodyHasNoRecommendationOrVerdict_acceptsIt() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        ReportBodyValidator validator = new ReportBodyValidator(casePaymentRepository);
        Map<String, Object> body = new HashMap<>(body());
        body.put(
                "paymentAnalyses",
                List.of(Map.of(
                        "paymentId",
                        "3",
                        "ruleHitDetails",
                        List.of(),
                        "basis",
                        Map.of("signals", List.of(Map.of("key", "R01", "level", "CAUTION")), "normals", List.of()))));

        assertThat(body).doesNotContainKey("verdict");
        assertThat(validator.validate(AiReport.pending(7L, 1, 1, NOW), body)).isEmpty();
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
                        "ruleHitDetails",
                        List.of(),
                        "basis",
                        Map.of(
                                "signals",
                                List.of(Map.of("key", "R01", "checkBefore", "구매 목적 확인")),
                                "normals",
                                List.of()))));

        assertThat(validator.validate(AiReport.pending(7L, 1, 1, NOW), withNewFields))
                .isEmpty();
    }

    // 배포 사이(Spring 먼저) 옛 ai-server가 권고 키를 담아 보내도 거부하지 않는다 (#644)
    // 화면 · 가맹점 API는 이 값을 읽지 않는다 (PR #646). 거부하면 배포 사이 보고서 저장이 모두 실패한다
    @Test
    void validate_whenOldAiServerSendsRecommendationKeys_stillAccepts() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 3L, NOW)));
        ReportBodyValidator validator = new ReportBodyValidator(casePaymentRepository);
        Map<String, Object> old = new HashMap<>(body());
        old.put(
                "paymentAnalyses",
                List.of(Map.of(
                        "paymentId",
                        "3",
                        "recommendation",
                        "REJECT_REVIEW",
                        "reason",
                        "고위험 징후를 포함해 정상 사유가 없는 징후가 겹칩니다",
                        "ruleHitDetails",
                        List.of(),
                        "basis",
                        Map.of(
                                "signals",
                                List.of(),
                                "normals",
                                List.of(),
                                "rule",
                                "HIGH_RISK_WITH_OTHERS",
                                "recommendation",
                                "REJECT_REVIEW"))));
        old.put("recommendation", Map.of("summary", "승인 취소를 먼저 검토할 것을 권고합니다.", "limitations", List.of()));
        old.put("verdict", Map.of("headline", "승인 취소 검토", "overallRecommendation", "REJECT_REVIEW"));

        assertThat(validator.validate(AiReport.pending(7L, 1, 1, NOW), old)).isEmpty();
    }

    private static Map<String, Object> body() {
        return Map.ofEntries(
                Map.entry("overview", Map.of("caseId", "7")),
                Map.entry("summary", List.of("요약")),
                Map.entry("paymentAnalyses", List.of(Map.of("paymentId", "3", "ruleHitDetails", List.of()))),
                Map.entry("timeline", List.of()),
                Map.entry("summaryFacts", List.of()),
                Map.entry("facts", List.of()),
                Map.entry("additionalChecks", List.of()),
                Map.entry("recommendation", Map.of("limitations", List.of())),
                Map.entry("evidence", List.of()));
    }
}
