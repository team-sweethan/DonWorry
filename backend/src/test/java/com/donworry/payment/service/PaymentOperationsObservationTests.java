package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.payment.dto.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.PaymentOperationRow;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentOperationsObservationTests {
    private final Instant now = Instant.parse("2026-10-26T00:00:00Z");
    // 자동 매입은 최초 결제 시각이 있어도 대기 시작을 추정하지 않는다.
    @Test
    void observation_whenAutomaticCapture_keepsWaitUnknown() {
        var p = Payment.pending(1L, "secret", "order", 100L, now.minusSeconds(300));
        p.recordAuthorizationSuccess("secret-authorization", false);
        var result = PaymentOperationsQueryService.observation(
                new PaymentOperationRow(p, null, null, PaymentOperationCategory.AUTO_CAPTURE_PENDING), now);
        assertThat(result.elapsedSeconds()).isEqualTo(300);
        assertThat(result.waitingBasis()).isEqualTo("UNKNOWN");
        assertThat(result.waitingSince()).isNull();
        assertThat(result.waitingSeconds()).isNull();
    }
    // HELD의 실제 시각 누락과 미래 기록은 0초로 위장하지 않는다.
    @Test
    void observation_whenMissingOrFutureTimestamp_preservesUncertainty() {
        var p = Payment.pending(1L, "secret", "order", 100L, now.plusSeconds(1));
        p.recordAuthorizationSuccess("authorization", true);
        ReflectionTestUtils.setField(p, "statusChangedAt", null);
        var result = PaymentOperationsQueryService.observation(
                new PaymentOperationRow(p, null, null, PaymentOperationCategory.DECISION_PENDING), now);
        assertThat(result.elapsedSeconds()).isNull();
        assertThat(result.waitingBasis()).isEqualTo("UNKNOWN");
        assertThat(result.waitingSince()).isNull();
        assertThat(result.limitations()).anyMatch(s -> s.contains("미래"));
    }
}
