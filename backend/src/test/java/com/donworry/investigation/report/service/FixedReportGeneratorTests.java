package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
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
                securityCaseRepository, casePaymentRepository, paymentInfoReader, similarCaseFinder);

        Map<String, Object> body = generator.generate(7L, 1, 1, NOW).orElseThrow();

        Map<?, ?> analysis = (Map<?, ?>) ((List<?>) body.get("paymentAnalyses")).getFirst();
        assertThat((List<?>) analysis.get("normalEvidence")).isEmpty();
        assertThat(analysis.get("reason")).isInstanceOf(String.class);
        assertThat(analysis.get("ruleHitDetails"))
                .isEqualTo(List.of(Map.<String, Object>of(
                        "code", "R01", "name", "고액 결제", "appliedScore", 30, "evidenceId", "E-7-v1-02")));
        assertThat(new ReportBodyValidator(casePaymentRepository).validate(AiReport.pending(7L, 1, 1, NOW), body))
                .isEmpty();
    }

    // AI 서버를 끈 시연에서도 경위가 ai-server와 같은 모양 — 결제 · 매입 보류 → 사건 생성, 같은 시각이면 결제 먼저 (#310)
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
                securityCaseRepository, casePaymentRepository, paymentInfoReader, similarCaseFinder);

        Map<String, Object> body = generator.generate(7L, 1, 1, NOW).orElseThrow();

        assertThat(body.get("timeline"))
                .isEqualTo(List.of(
                        Map.of(
                                "at",
                                NOW.toString(),
                                "event",
                                "결제 15 · 600,000원 → FDS 검토 60점 → 매입 보류",
                                "kind",
                                "PAYMENT"),
                        Map.of("at", NOW.toString(), "event", "사건 자동 생성 · AI 조사 시작", "kind", "CASE")));
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
                securityCaseRepository, casePaymentRepository, paymentInfoReader, similarCaseFinder);

        Map<String, Object> body = generator.generate(7L, 1, 1, NOW).orElseThrow();

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

        Map<String, Object> body = generator.generate(7L, 1, 1, NOW).orElseThrow();

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

        Map<String, Object> body = generator.generate(7L, 1, 1, NOW).orElseThrow();

        assertThat(body).containsKey("similarCases");
        assertThat(body.get("similarCases")).isNull();
        assertThat(body.get("verdict")).isNotNull();
        // 조회하지 못한 자료는 증거 추적 표에 조회한 것처럼 남기지 않는다 (#296 리뷰)
        assertThat(evidenceOf(body, "find_similar_cases")).isEmpty();
        assertThat(evidenceOf(body, "get_fds_evidence")).hasSize(1);
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
                securityCaseRepository, casePaymentRepository, paymentInfoReader, similarCaseFinder);
    }
}
