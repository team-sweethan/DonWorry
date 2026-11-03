package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
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
                linkedPaymentFinder);

        FdsEvidenceToolResponse response = service.fdsEvidence(7L, List.of());

        assertThat(response.payments())
                .extracting(FdsEvidenceToolResponse.Payment::paymentId, FdsEvidenceToolResponse.Payment::signals)
                .containsExactly(tuple("1", null), tuple("2", card));
    }
}
