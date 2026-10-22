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
