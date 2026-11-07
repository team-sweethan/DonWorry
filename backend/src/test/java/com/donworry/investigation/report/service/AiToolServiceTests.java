package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse.Signals;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// AI Tool 조회 규칙 — 저장소 · 소유 모듈 창구는 목으로 바꿔 결제별 짝 맞추기만 본다
@ExtendWith(MockitoExtension.class)
class AiToolServiceTests {

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

    // FDS 평가가 없는 결제는 카드 정보가 비고, 다른 결제의 카드 정보가 붙지 않는다 (#346)
    @Test
    void fdsEvidence_noEvaluation_signalsNull() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 1L, NOW), CasePayment.link(securityCase, 2L, NOW)));
        when(paymentInfoReader.findAll(List.of(1L, 2L)))
                .thenReturn(Map.of(
                        1L, new PaymentInfo(1L, 600_000L, "HELD", NOW, null, null, List.of()),
                        2L, new PaymentInfo(2L, 120_000L, "HELD", NOW, 60, "REVIEW", List.of())));
        Signals card = new Signals("신한 ****1234", true, true, "KR", true);
        when(paymentSignalReader.findAll(List.of(1L, 2L))).thenReturn(Map.of(2L, card));
        AiToolService service = new AiToolService(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);

        FdsEvidenceToolResponse response = service.fdsEvidence(7L, List.of());

        assertThat(response.payments())
                .extracting(FdsEvidenceToolResponse.Payment::paymentId, FdsEvidenceToolResponse.Payment::signals)
                .containsExactly(tuple("1", null), tuple("2", card));
    }

    // 징후 계산용 고객 이력 조회가 실패해도 징후는 나온다 — 정상 사유 없이 계산한다 (#618)
    // 고객 이력은 30일 기본 조회만 본다 (#581, AI가 90일을 불렀는지와 상관없이 같은 근거)
    @Test
    void decisionBasis_historyLookupFails_computesWithoutNormalReason() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 1L, NOW)));
        when(paymentInfoReader.findAll(List.of(1L)))
                .thenReturn(Map.of(
                        1L,
                        new PaymentInfo(
                                1L,
                                600_000L,
                                "HELD",
                                NOW,
                                60,
                                "REVIEW",
                                List.of(new PaymentInfoReader.RuleHit("R01", "고액 결제", 30)))));
        when(paymentHistoryFinder.find(eq(7L), any(Instant.class), eq(PaymentHistoryFinder.DEFAULT_LOOKBACK_DAYS)))
                .thenThrow(new IllegalStateException("조회 실패"));
        // 연결 결제는 조회에 성공했고 다른 고객 결제가 없다 (null은 조회 실패라 근거를 주지 않는다)
        when(linkedPaymentFinder.find(eq(7L), any(Instant.class))).thenReturn(FixedReportGeneratorTests.noLinks(NOW));
        AiToolService service = new AiToolService(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);

        DecisionBasisToolResponse response = service.decisionBasis(7L);

        DecisionBasisToolResponse.Basis basis = response.payments().getFirst().basis();
        assertThat(response.payments())
                .extracting(DecisionBasisToolResponse.PaymentBasis::paymentId)
                .containsExactly("1");
        assertThat(basis.normals()).isEmpty();
        assertThat(basis.signals()).allMatch(s -> s.normalReason() == null);
    }

    // 징후 계산용 같은 카드 · 기기 · IP 조회가 실패하면 근거를 주지 않는다 (#618 Codex 리뷰)
    // "연결 징후 없음"으로 계산하면 고위험 징후(같은 카드 · 기기)가 빠진 근거가 "징후 없음"처럼 나간다
    // 빈 목록이면 ai-server가 한계 줄로 알린다
    @Test
    void decisionBasis_linkedLookupFails_providesNoBasis() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 1L, NOW)));
        when(paymentInfoReader.findAll(List.of(1L)))
                .thenReturn(Map.of(
                        1L,
                        new PaymentInfo(
                                1L,
                                600_000L,
                                "HELD",
                                NOW,
                                60,
                                "REVIEW",
                                List.of(new PaymentInfoReader.RuleHit("R01", "고액 결제", 30)))));
        when(paymentHistoryFinder.find(eq(7L), any(Instant.class), eq(PaymentHistoryFinder.DEFAULT_LOOKBACK_DAYS)))
                .thenReturn(FixedReportGeneratorTests.usualHistory(NOW));
        when(linkedPaymentFinder.find(eq(7L), any(Instant.class))).thenThrow(new IllegalStateException("조회 실패"));
        AiToolService service = new AiToolService(
                securityCaseRepository,
                casePaymentRepository,
                paymentInfoReader,
                similarCaseFinder,
                paymentHistoryFinder,
                paymentSignalReader,
                linkedPaymentFinder,
                orderContextFinder);

        DecisionBasisToolResponse response = service.decisionBasis(7L);

        assertThat(response.payments()).isEmpty();
    }
}
