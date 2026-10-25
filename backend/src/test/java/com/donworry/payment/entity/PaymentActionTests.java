package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;

import com.donworry.payment.api.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentActionTests {
    private static final Instant NOW = Instant.parse("2026-10-25T07:00:00.123456789Z");

    private PaymentAction action(PaymentActionType type) {
        return PaymentAction.pending(1L, 2L, type, UUID.randomUUID(), NOW);
    }

    // 같은 매입 완료는 허용하고 다른 결과로 변경하거나 실패로 되돌리지 않는다.
    @Test
    void completeCapture_whenRepeated_preservesOriginalResult() {
        var action = action(PaymentActionType.CAPTURE);
        action.completeCapture(10L, NOW);
        action.completeCapture(10L, NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(action.getStatus()).isEqualTo(PaymentActionStatus.SUCCEEDED);
        assertThat(action.getCompletedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(action.getRequestedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThatIllegalStateException().isThrownBy(() -> action.completeCapture(11L, NOW));
        assertThatIllegalStateException().isThrownBy(() -> action.completeCapture(10L, NOW.plusSeconds(1)));
        assertThatIllegalStateException().isThrownBy(() -> action.fail("FAIL", NOW));
        assertThat(action.getCaptureId()).isEqualTo(10L);
    }

    // 반대 종류의 실행과 잘못된 완료 값은 상태를 변경하지 않는다.
    @Test
    void complete_whenWrongTypeOrInvalid_doesNotMutate() {
        var capture = action(PaymentActionType.CAPTURE);
        var cancel = action(PaymentActionType.VOID);
        assertThatIllegalStateException().isThrownBy(() -> capture.completeVoid(NOW));
        assertThatIllegalStateException().isThrownBy(() -> cancel.completeCapture(1L, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> capture.completeCapture(0L, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> cancel.fail(" ", NOW));
        assertThat(capture.getStatus()).isEqualTo(PaymentActionStatus.PENDING);
        assertThat(cancel.getStatus()).isEqualTo(PaymentActionStatus.PENDING);
    }

    // 확정 업무 실패는 같은 행에서 다시 성공 처리하지 않는다.
    @Test
    void fail_whenTerminal_blocksFurtherExecution() {
        var action = action(PaymentActionType.VOID);
        action.fail("VOID_DECLINED", NOW);
        assertThat(action.getErrorCode()).isEqualTo("VOID_DECLINED");
        assertThat(action.getCaptureId()).isNull();
        assertThatIllegalStateException().isThrownBy(() -> action.completeVoid(NOW));
    }

    // 키뿐 아니라 원 요청의 모든 항목이 같아야 재요청이다.
    @Test
    void matches_whenPayloadChanges_returnsFalse() {
        var key = UUID.randomUUID();
        var action = PaymentAction.pending(1L, 2L, PaymentActionType.VOID, key, NOW);
        assertThat(action.matches(1L, 2L, PaymentActionType.VOID, key)).isTrue();
        assertThat(action.matches(3L, 2L, PaymentActionType.VOID, key)).isFalse();
        assertThat(action.matches(1L, 3L, PaymentActionType.VOID, key)).isFalse();
        assertThat(action.matches(1L, 2L, PaymentActionType.CAPTURE, key)).isFalse();
        assertThat(action.matches(1L, 2L, PaymentActionType.VOID, UUID.randomUUID()))
                .isFalse();
    }

    // 공개 응답은 성공 ID 누락·대기 오류 혼합·UNKNOWN을 허용하지 않는다.
    @Test
    void receipt_whenContradictory_rejects() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentActionReceipt("ACCEPTED", null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentActionReceipt("PENDING", 1L, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentActionReceipt("UNKNOWN", null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentActionReceipt.rejected(" "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentActionCommand(0L, 1L, "VOID", UUID.randomUUID()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentActionCommand(1L, 1L, "void", UUID.randomUUID()));
    }

    // 완료 이벤트는 대기 상태를 거절하고 결과 시각을 마이크로초로 보존한다.
    @Test
    void result_whenPending_rejectsAndSuccessPreservesFields() {
        var key = UUID.randomUUID();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentActionResult(1L, 2L, "CAPTURE", key, "PENDING", null, null, NOW));
        var result = PaymentActionResult.builder()
                .paymentId(1L)
                .decisionId(2L)
                .actionType("VOID")
                .idempotencyKey(key)
                .status("ACCEPTED")
                .targetOperationId(3L)
                .completedAt(NOW)
                .build();
        assertThat(result.completedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(result.targetOperationId()).isEqualTo(3L);
        assertThat(result.idempotencyKey()).isEqualTo(key);
    }
}
