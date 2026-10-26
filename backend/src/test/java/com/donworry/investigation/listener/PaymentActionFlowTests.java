package com.donworry.investigation.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CaseDetailResponse.AiReportResponse;
import com.donworry.investigation.dto.CaseDetailResponse.HistoryResponse;
import com.donworry.investigation.dto.DecisionSubmitRequest;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.service.CaseDecisionService;
import com.donworry.investigation.service.CaseIntakeService;
import com.donworry.investigation.service.CaseQueryService;
import com.donworry.investigation.service.HeldPayment;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 실제 P5 PaymentActionRequester · P6 매입까지 이어서 판정 → 금융 조치 완료 → 인계 ACCEPTED → Case 종료를 확인한다 (#161)
// P5 공개 결제 입력이 꺼져 있어(#151) HELD 결제는 P5 테스트와 같은 방법으로 P5 저장소에 직접 만든다 — 테스트 준비에만 쓴다
// 커밋 뒤 비동기 리스너(P6 매입 · P5 결과 · P3 반영)가 돌아야 하므로 롤백 테스트가 아니다
// 이 테스트만 쓰는 설정이라 컨텍스트가 따로 뜬다 — 끝나면 닫아 공유 MariaDB의 연결 한도를 넘지 않는다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentActionFlowTests {

    // 전체 mariaDbTest 중에는 비동기 처리가 늦어질 수 있다 (#178). 조건이 맞으면 바로 끝난다
    static final Duration WAIT = Duration.ofSeconds(30);

    @MockitoBean
    CurrentStaff currentStaff;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseDecisionService caseDecisionService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbc;

    // 판정 ID 시작값 — P5 테스트는 같은 DB에 decisionId = paymentId인 임의 조치를 남긴다
    // P3 판정 ID가 그 값과 겹치면 P5가 DECISION_ACTION_CONFLICT로 거절하므로, 이 테스트의 판정은 겹치지 않는 범위에서 만든다
    static final long DECISION_ID_FLOOR = 8_000_000_000L;

    @BeforeEach
    void loginAsSecurityStaff() {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(
                        StaffSummary.builder().userId(7L).role("SECURITY_STAFF").build()));
        // 현재 최댓값보다 작으면 MariaDB가 무시하므로 여러 번 실행해도 된다
        jdbc.execute("ALTER TABLE payment_decisions AUTO_INCREMENT = " + DECISION_ID_FLOOR);
    }

    // ACCEPT 판정이면 P5가 처리 중으로 답하고, P6 매입이 끝난 뒤 결과 이벤트로 인계 완료 · Case 종료가 된다
    @Test
    void accept_afterCaptureCompleted_acceptsHandoffAndClosesCase() {
        Long caseId = decide(Decision.ACCEPT);

        CaseDetailResponse closed = awaitCase(caseId, "CLOSED");

        assertThat(closed.handoffs()).singleElement().satisfies(handoff -> {
            assertThat(handoff.actionType()).isEqualTo("CAPTURE");
            assertThat(handoff.status()).isEqualTo("ACCEPTED");
            assertThat(handoff.targetOperationId()).isNotNull();
        });
        assertThat(acceptedHistory(closed).eventData()).containsKey("completedAt");
        assertThat(paymentStatus(closed)).isEqualTo("CAPTURED");
    }

    // REJECT 판정이면 P5 모의 취소가 끝난 뒤 결과 이벤트로 인계 완료 · Case 종료가 된다
    @Test
    void reject_afterVoidCompleted_acceptsHandoffAndClosesCase() {
        Long caseId = decide(Decision.REJECT);

        CaseDetailResponse closed = awaitCase(caseId, "CLOSED");

        assertThat(closed.handoffs()).singleElement().satisfies(handoff -> {
            assertThat(handoff.actionType()).isEqualTo("VOID");
            assertThat(handoff.status()).isEqualTo("ACCEPTED");
        });
        assertThat(acceptedHistory(closed).eventData()).containsKey("completedAt");
        assertThat(paymentStatus(closed)).isEqualTo("VOIDED");
    }

    private Long decide(Decision decision) {
        String customerRef = "customer-" + UUID.randomUUID();
        Long paymentId = transactionTemplate.execute(status -> {
            Payment payment = Payment.pending(1L, customerRef, "order-" + UUID.randomUUID(), 150_000L, Instant.now());
            payment.recordAuthorizationSuccess("AUTH-" + UUID.randomUUID(), true);
            return paymentRepository.save(payment).getPaymentId();
        });
        Long caseId = caseIntakeService.receive(
                new HeldPayment(paymentId, 1L, customerRef, 150_000L, "AUTH", null, Instant.now()));
        CaseDetailResponse detail = caseQueryService.detail(caseId);
        // 목 보고서는 P3 목 결제 정보가 없으면 최종 실패로 남는다 — 그때는 보고서 없이 수동 판정한다
        AiReportResponse report = detail.reports().getLast();
        caseDecisionService.decide(
                caseId,
                new DecisionSubmitRequest(
                        detail.securityCase().evidenceVersion(),
                        "FAILED".equals(report.status()) ? null : report.reportId(),
                        List.of(new DecisionSubmitRequest.Item(
                                detail.payments().getFirst().casePaymentId(), decision, "사유"))));
        return caseId;
    }

    private CaseDetailResponse awaitCase(Long caseId, String status) {
        return await(() -> Optional.of(caseQueryService.detail(caseId))
                .filter(detail -> detail.securityCase().status().equals(status)));
    }

    private String paymentStatus(CaseDetailResponse detail) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE payment_id = ?",
                String.class,
                detail.payments().getFirst().paymentId());
    }

    private static HistoryResponse acceptedHistory(CaseDetailResponse detail) {
        return detail.history().stream()
                .filter(history -> history.eventType().equals("HANDOFF_ACCEPTED"))
                .findFirst()
                .orElseThrow();
    }

    private static <T> T await(Supplier<Optional<T>> query) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            Optional<T> found = query.get();
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("금융 조치 결과가 " + WAIT + " 안에 반영되지 않았습니다");
    }
}
