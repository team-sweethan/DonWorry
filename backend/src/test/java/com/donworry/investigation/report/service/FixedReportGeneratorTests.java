package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
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
        FixedReportGenerator generator =
                new FixedReportGenerator(securityCaseRepository, casePaymentRepository, paymentInfoReader);

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
}
