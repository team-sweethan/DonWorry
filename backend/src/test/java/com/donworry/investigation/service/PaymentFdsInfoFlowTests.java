package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.api.FdsEvidenceReader;
import com.donworry.fds.api.FdsPaymentEvidence;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CaseDetailResponse.RuleHitResponse;
import com.donworry.investigation.dto.CaseSummaryResponse;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

// 실제 P5 결제 · P2 평가가 Case 화면 값으로 나오는지 확인한다 (#163)
// P5 공개 결제 입력이 꺼져 있어(#151) 결제는 P5 테스트와 같은 방법으로 P5 저장소에 직접 만든다 — 테스트 준비에만 쓴다
// P2 평가는 공개 FdsEvaluator로 만들고, 룰 설정에 따라 값이 달라지므로 기대값은 P2 FdsEvidenceReader 결과와 비교한다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentFdsInfoFlowTests {

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    FdsEvaluator fdsEvaluator;

    @Autowired
    FdsEvidenceReader fdsEvidenceReader;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    TransactionTemplate transactionTemplate;

    // 실제 결제와 평가가 있으면 금액 · 결제 상태 · 점수 · 판정 · 적중 룰이 P5 · P2 값 그대로 나오고, 목 보고서도 성공한다
    @Test
    void detail_withRealPaymentAndEvaluation_showsPaymentAndFdsValues() {
        String customerRef = "customer-" + UUID.randomUUID();
        Long paymentId = heldPayment(customerRef, 150_000L);
        fdsEvaluator.evaluate(FdsEvaluationRequest.builder()
                .evaluationRequestId(UUID.randomUUID())
                .paymentId(paymentId)
                .merchantId(1L)
                .customerRef(customerRef)
                .amount(150_000L)
                .paymentRequestedAt(Instant.now())
                .build());
        FdsPaymentEvidence evidence =
                fdsEvidenceReader.findLatestByPaymentIds(List.of(paymentId)).getFirst();

        Long caseId = receive(paymentId, customerRef, 150_000L);

        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.amount()).isEqualTo(150_000L);
            assertThat(payment.paymentStatus()).isEqualTo("HELD");
            assertThat(payment.requestedAt()).isNotNull();
            assertThat(payment.riskScore()).isEqualTo(evidence.riskScore());
            assertThat(payment.fdsResult()).isEqualTo(evidence.result().name());
            assertThat(payment.ruleHits())
                    .containsExactlyElementsOf(evidence.matchedRules().stream()
                            .map(rule -> new RuleHitResponse(rule.ruleCode(), rule.ruleName(), rule.appliedScore()))
                            .toList());
        });
        assertThat(summaryOf(caseId).totalAmount()).isEqualTo(150_000L);
        if (evidence.riskScore() != null) {
            assertThat(detail.reports().getLast().status()).isEqualTo("SUCCEEDED");
        }
    }

    // P2 평가가 없으면 결제 값만 나오고, 점수를 몰라 목록 최고 점수도 null · 보고서는 FAILED다
    @Test
    void detail_withoutEvaluation_showsPaymentOnly() {
        String customerRef = "customer-" + UUID.randomUUID();
        Long paymentId = heldPayment(customerRef, 90_000L);

        Long caseId = receive(paymentId, customerRef, 90_000L);

        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.amount()).isEqualTo(90_000L);
            assertThat(payment.riskScore()).isNull();
            assertThat(payment.fdsResult()).isNull();
            assertThat(payment.ruleHits()).isEmpty();
        });
        CaseSummaryResponse summary = summaryOf(caseId);
        assertThat(summary.totalAmount()).isEqualTo(90_000L);
        assertThat(summary.maxRiskScore()).isNull();
        assertThat(detail.reports().getLast().status()).isEqualTo("FAILED");
    }

    private Long heldPayment(String customerRef, long amount) {
        return transactionTemplate.execute(status -> {
            Payment payment = Payment.pending(1L, customerRef, "order-" + UUID.randomUUID(), amount, Instant.now());
            payment.recordAuthorizationSuccess("AUTH-" + UUID.randomUUID(), true);
            return paymentRepository.save(payment).getPaymentId();
        });
    }

    private Long receive(Long paymentId, String customerRef, long amount) {
        return caseIntakeService.receive(
                new HeldPayment(paymentId, 1L, customerRef, amount, "AUTH", null, Instant.now()));
    }

    private CaseSummaryResponse summaryOf(Long caseId) {
        return caseQueryService.list(null).stream()
                .filter(summary -> summary.caseId().equals(caseId))
                .findFirst()
                .orElseThrow();
    }
}
