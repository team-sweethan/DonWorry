package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentCancellationTests {
    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

    private PaymentCancellation start(PaymentCancellationOperation operation, Long captureId) {
        var payment = mock(Payment.class);
        when(payment.getPaymentId()).thenReturn(1L);
        when(payment.getMerchantId()).thenReturn(2L);
        when(payment.getAmount()).thenReturn(300L);
        var request = mock(PaymentCancellationRequest.class);
        when(request.getRequestId()).thenReturn(4L);
        when(request.getPaymentId()).thenReturn(1L);
        when(request.getMerchantId()).thenReturn(2L);
        when(request.getResolution()).thenReturn(PaymentCancellationResolution.ACCEPTED);
        when(request.getUpdatedAt()).thenReturn(NOW);
        return PaymentCancellation.start(request, payment, operation, captureId, NOW);
    }

    // 미확정은 완료 시각 없이 보존하고 확인된 성공만 종료한다
    @Test
    void succeed_whenUnknown_preservesIdentityAndRejectsConflictingReplay() {
        var work = start(PaymentCancellationOperation.REFUND, 5L);
        work.markUnknown(NOW.plusSeconds(1));
        assertThat(work.getCompletedAt()).isNull();
        assertThat(work.getErrorCode()).isNull();
        work.markUnknown(NOW.plusSeconds(2));
        assertThat(work.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
        work.succeed(NOW.plusSeconds(3));
        work.succeed(NOW.plusSeconds(3));
        assertThat(work.getStatus()).isEqualTo(PaymentCancellationStatus.SUCCEEDED);
        assertThat(work.getAmount()).isEqualTo(300);
        assertThat(work.getSourceRequestId()).isEqualTo(4);
        assertThat(work.getCaptureId()).isEqualTo(5);
        assertThatIllegalStateException().isThrownBy(() -> work.succeed(NOW.plusSeconds(4)));
        assertThatIllegalStateException().isThrownBy(() -> work.fail("DECLINED", NOW.plusSeconds(3)));
        assertThatIllegalStateException().isThrownBy(() -> work.markUnknown(NOW.plusSeconds(4)));
    }

    // 확정 실패도 같은 결과만 재반영하며 새 실행 상태로 돌아가지 않는다
    @Test
    void fail_whenRepeated_preservesResultAndCompletionTime() {
        var work = start(PaymentCancellationOperation.VOID, null);
        work.fail("DECLINED", NOW.plusSeconds(1));
        work.fail("DECLINED", NOW.plusSeconds(1));
        assertThat(work.getErrorCode()).isEqualTo("DECLINED");
        assertThat(work.getCompletedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThatIllegalStateException().isThrownBy(() -> work.fail("OTHER", NOW.plusSeconds(1)));
        assertThatIllegalStateException().isThrownBy(() -> work.succeed(NOW.plusSeconds(1)));
    }

    // 잘못된 매입 근거와 과거 시각은 객체 상태를 바꾸지 않는다
    @Test
    void transition_whenInvalidEvidenceOrTime_rejectsWithoutMutation() {
        assertThatIllegalArgumentException().isThrownBy(() -> start(PaymentCancellationOperation.REFUND, null));
        assertThatIllegalArgumentException().isThrownBy(() -> start(PaymentCancellationOperation.VOID, 5L));
        var work = start(PaymentCancellationOperation.VOID, null);
        assertThatIllegalArgumentException().isThrownBy(() -> work.succeed(NOW.minusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> work.fail(" ", NOW));
        assertThat(work.getStatus()).isEqualTo(PaymentCancellationStatus.PROCESSING);
        assertThat(work.getCompletedAt()).isNull();
    }
}
