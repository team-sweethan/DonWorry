package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 결제 상태 웹훅의 재전송 예약과 한도 (ADR-0064 3절, #455)
class PaymentSessionNotifyTests {
    private static final Instant TIME = Instant.parse("2026-11-03T00:00:00.123456Z");

    // 실패할수록 5초부터 두 배씩 늘리고 5분을 넘기지 않는다
    @Test
    void notifyFailed_beforeLimit_schedulesWithBackoff() {
        PaymentSession session = submittedSession();

        assertThat(session.notifyFailed("HELD", true, TIME)).isFalse();
        assertThat(session.getNextNotifyAt()).isEqualTo(TIME.plusSeconds(10));
        for (int i = 2; i < 7; i++) session.notifyFailed("HELD", true, TIME);

        assertThat(session.getNotifyAttempts()).isEqualTo(6);
        assertThat(session.getNextNotifyAt()).isEqualTo(TIME.plus(5, ChronoUnit.MINUTES));
        assertThat(session.getNotifiedStatus()).isNull();
    }

    // 한도 직전까지는 다시 보내고, 한도에 닿으면 그 상태를 처리한 것으로 기록하고 그만둔다
    @Test
    void notifyFailed_atLimit_stopsAndMarksStatusHandled() {
        PaymentSession session = submittedSession();
        for (int i = 1; i < PaymentSession.NOTIFY_MAX_ATTEMPTS; i++) {
            assertThat(session.notifyFailed("HELD", true, TIME)).isFalse();
        }
        assertThat(session.getNextNotifyAt()).isNotNull();
        assertThat(session.getNotifiedStatus()).isNull();

        assertThat(session.notifyFailed("HELD", true, TIME)).isTrue();

        assertThat(session.getNextNotifyAt()).isNull();
        assertThat(session.getNotifiedStatus()).isEqualTo("HELD");
        assertThat(session.getNotifiedAuthorized()).isTrue();
        assertThat(session.getNotifyAttempts()).isEqualTo(PaymentSession.NOTIFY_MAX_ATTEMPTS);
    }

    // 그만둔 상태는 다시 예약하지 않고, 상태가 바뀌면 횟수를 새로 세어 다시 알린다
    @Test
    void scheduleNotifyIfChanged_afterGivingUp_onlyReschedulesNewStatus() {
        PaymentSession session = submittedSession();
        for (int i = 0; i < PaymentSession.NOTIFY_MAX_ATTEMPTS; i++) session.notifyFailed("HELD", true, TIME);

        session.scheduleNotifyIfChanged("HELD", true, TIME.plusSeconds(1));
        assertThat(session.getNextNotifyAt()).isNull();

        session.scheduleNotifyIfChanged("VOIDED", true, TIME.plusSeconds(2));
        assertThat(session.getNextNotifyAt()).isEqualTo(TIME.plusSeconds(2));
        assertThat(session.getNotifyAttempts()).isZero();
    }

    private static PaymentSession submittedSession() {
        PaymentSession session = PaymentSession.builder()
                .sessionKey("pss_notify")
                .merchantId(1L)
                .requestKey(UUID.randomUUID())
                .attemptKey(UUID.randomUUID())
                .orderRef("order")
                .customerRef("42")
                .amount(4000L)
                .orderCreatedAt(TIME.minusSeconds(10))
                .orderName("웹훅 한도 확인 주문")
                .successUrl("https://shop.example/success")
                .failUrl("https://shop.example/fail")
                .notifyUrl("https://shop.example/hooks/donworry")
                .expiresAt(TIME.plus(10, ChronoUnit.MINUTES))
                .createdAt(TIME)
                .build();
        session.submitted(7L, TIME);
        return session;
    }
}
