package com.donworry.investigation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InvestigationEntityTests {

    static final Instant NOW = Instant.parse("2026-10-22T05:00:00Z");

    // 새 Case는 OPEN · 증거 버전 1로 시작한다
    @Test
    void securityCase_open_startsOpenWithFirstEvidenceVersion() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);

        assertThat(securityCase.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(securityCase.getEvidenceVersion()).isEqualTo(1);
        assertThat(securityCase.getClosedAt()).isNull();
    }

    // 결제가 병합되면 증거 버전이 하나씩 오른다
    @Test
    void securityCase_increaseEvidenceVersion_incrementsByOne() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);

        securityCase.increaseEvidenceVersion();

        assertThat(securityCase.getEvidenceVersion()).isEqualTo(2);
    }

    // 종료는 close()로만 하고 종료 시각을 남긴다
    @Test
    void securityCase_changeStatusToClosed_throwsException() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);

        assertThatIllegalArgumentException().isThrownBy(() -> securityCase.changeStatus(CaseStatus.CLOSED));
    }

    // 종료된 Case는 상태 · 증거 버전을 바꿀 수 없다
    @Test
    void securityCase_whenClosed_rejectsChanges() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        securityCase.close(NOW.plusSeconds(60));

        assertThat(securityCase.getClosedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatIllegalStateException().isThrownBy(() -> securityCase.changeStatus(CaseStatus.ACTION_FAILED));
        assertThatIllegalStateException().isThrownBy(securityCase::increaseEvidenceVersion);
        assertThatIllegalStateException().isThrownBy(() -> securityCase.close(NOW.plusSeconds(120)));
    }

    // ACCEPT 판정은 CAPTURE, REJECT 판정은 VOID 인계가 된다
    @Test
    void actionHandoff_request_takesActionTypeFromDecision() {
        assertThat(handoffFor(Decision.ACCEPT).getActionType()).isEqualTo(ActionType.CAPTURE);
        assertThat(handoffFor(Decision.REJECT).getActionType()).isEqualTo(ActionType.VOID);
    }

    // 새 인계는 REQUESTED이고 최초 요청 시각과 마지막 시도 시각이 같다
    @Test
    void actionHandoff_request_startsRequestedWithKey() {
        ActionHandoff handoff = handoffFor(Decision.ACCEPT);

        assertThat(handoff.getStatus()).isEqualTo(HandoffStatus.REQUESTED);
        assertThat(handoff.getIdempotencyKey()).isNotNull();
        assertThat(handoff.getLastAttemptedAt()).isEqualTo(handoff.getRequestedAt());
        assertThat(handoff.getTargetOperationId()).isNull();
    }

    // 재시도는 같은 멱등키를 유지하고 최초 요청 시각을 바꾸지 않는다
    @Test
    void actionHandoff_retryAfterFail_keepsKeyAndRequestedAt() {
        ActionHandoff handoff = handoffFor(Decision.REJECT);
        var key = handoff.getIdempotencyKey();
        handoff.fail("PAYMENT_REJECTED");

        handoff.retry(NOW.plusSeconds(300));

        assertThat(handoff.getStatus()).isEqualTo(HandoffStatus.REQUESTED);
        assertThat(handoff.getIdempotencyKey()).isEqualTo(key);
        assertThat(handoff.getRequestedAt()).isEqualTo(NOW);
        assertThat(handoff.getLastAttemptedAt()).isEqualTo(NOW.plusSeconds(300));
    }

    // 접수가 확인된 인계는 다시 실패 · 재시도로 바꿀 수 없다
    @Test
    void actionHandoff_whenAccepted_rejectsChanges() {
        ActionHandoff handoff = handoffFor(Decision.ACCEPT);
        handoff.accept(10L, NOW.plusSeconds(5));

        assertThat(handoff.getAcceptedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThatIllegalStateException().isThrownBy(() -> handoff.fail("LATE_FAILURE"));
        assertThatIllegalStateException().isThrownBy(() -> handoff.retry(NOW.plusSeconds(10)));
    }

    // 같은 접수 결과가 다시 오면 예외 없이 그대로 둔다 (이벤트 재전달 멱등)
    @Test
    void actionHandoff_acceptSameOperationTwice_isNoOp() {
        ActionHandoff handoff = handoffFor(Decision.ACCEPT);
        handoff.accept(10L, NOW.plusSeconds(5));

        handoff.accept(10L, NOW.plusSeconds(30));

        assertThat(handoff.getStatus()).isEqualTo(HandoffStatus.ACCEPTED);
        assertThat(handoff.getAcceptedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    // 이미 접수된 인계에 다른 작업 ID가 오면 충돌로 거부한다
    @Test
    void actionHandoff_acceptDifferentOperation_throwsException() {
        ActionHandoff handoff = handoffFor(Decision.ACCEPT);
        handoff.accept(10L, NOW.plusSeconds(5));

        assertThatIllegalStateException().isThrownBy(() -> handoff.accept(11L, NOW.plusSeconds(30)));
        assertThat(handoff.getTargetOperationId()).isEqualTo(10L);
    }

    // 저장되는 시각은 DATETIME(6)에 맞춰 마이크로초로 잘린다
    @Test
    void entities_truncateInstantsToMicros() {
        Instant withNanos = NOW.plusNanos(123_456_789);
        Instant micros = NOW.plusNanos(123_456_000);
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, withNanos);
        CasePayment casePayment = CasePayment.link(securityCase, 100L, withNanos);
        PaymentDecision decision = PaymentDecision.confirm(casePayment, 3L, 7L, Decision.ACCEPT, "사유", 1, withNanos);
        ActionHandoff handoff = ActionHandoff.request(decision, withNanos);
        handoff.accept(10L, withNanos);
        securityCase.close(withNanos);

        assertThat(securityCase.getCreatedAt()).isEqualTo(micros);
        assertThat(securityCase.getClosedAt()).isEqualTo(micros);
        assertThat(casePayment.getLinkedAt()).isEqualTo(micros);
        assertThat(decision.getDecidedAt()).isEqualTo(micros);
        assertThat(handoff.getRequestedAt()).isEqualTo(micros);
        assertThat(handoff.getLastAttemptedAt()).isEqualTo(micros);
        assertThat(handoff.getAcceptedAt()).isEqualTo(micros);
        assertThat(CaseHistory.bySystem(securityCase, CaseHistoryEventType.CASE_CREATED, null, withNanos)
                        .getCreatedAt())
                .isEqualTo(micros);
    }

    // AI 보고서 없이 저장한 판정은 수동 판정이다
    @Test
    void paymentDecision_withoutReport_isManual() {
        PaymentDecision decision = decisionFor(Decision.REJECT, null);

        assertThat(decision.isManual()).isTrue();
        assertThat(decisionFor(Decision.ACCEPT, 7L).isManual()).isFalse();
    }

    private static ActionHandoff handoffFor(Decision decision) {
        return ActionHandoff.request(decisionFor(decision, 7L), NOW);
    }

    private static PaymentDecision decisionFor(Decision decision, Long reportId) {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        CasePayment casePayment = CasePayment.link(securityCase, 100L, NOW);
        return PaymentDecision.confirm(casePayment, 3L, reportId, decision, "사유", 1, NOW);
    }
}
