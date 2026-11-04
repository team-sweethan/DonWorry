package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 결제 상태 웹훅의 재전송 예약과 한도 (ADR-0064 3절, #455)
class PaymentSessionNotifyTests {
    private static final Instant TIME = Instant.parse("2026-11-03T00:00:00.123456Z");
    private static final String HELD_EVENT = "evt_7_HELD_A";
    private static final String VOIDED_EVENT = "evt_7_VOIDED_A";

    // 실패할수록 5초부터 두 배씩 늘리고 5분을 넘기지 않는다 (첫 재시도는 5초 뒤, #460)
    @Test
    void notifyFailed_beforeLimit_schedulesWithBackoff() {
        PaymentSession session = submittedSession();

        for (long seconds : new long[] {5, 10, 20, 40, 80, 160, 300, 300}) {
            assertThat(session.notifyFailed(HELD_EVENT, "HELD", true, TIME)).isFalse();
            assertThat(session.getNextNotifyAt()).isEqualTo(TIME.plusSeconds(seconds));
        }

        assertThat(session.getNotifyAttempts()).isEqualTo(8);
        assertThat(session.getNotifyEventId()).isEqualTo(HELD_EVENT);
        assertThat(session.getNotifiedStatus()).isNull();
    }

    // 한도 직전까지는 다시 보내고, 한도에 닿으면 그 상태를 처리한 것으로 기록하고 그만둔다
    @Test
    void notifyFailed_atLimit_stopsAndMarksStatusHandled() {
        PaymentSession session = submittedSession();
        for (int i = 1; i < PaymentSession.NOTIFY_MAX_ATTEMPTS; i++) {
            assertThat(session.notifyFailed(HELD_EVENT, "HELD", true, TIME)).isFalse();
        }
        assertThat(session.getNextNotifyAt()).isNotNull();
        assertThat(session.getNotifiedStatus()).isNull();

        assertThat(session.notifyFailed(HELD_EVENT, "HELD", true, TIME)).isTrue();

        assertThat(session.getNextNotifyAt()).isNull();
        assertThat(session.getNotifiedStatus()).isEqualTo("HELD");
        assertThat(session.getNotifiedAuthorized()).isTrue();
        assertThat(session.getNotifyAttempts()).isEqualTo(PaymentSession.NOTIFY_MAX_ATTEMPTS);
    }

    // 재시도 중 상태가 바뀌어 보낼 eventId가 달라지면 앞 상태의 실패를 이어 세지 않고 횟수 · 간격을 처음부터 센다
    @Test
    void notifyFailed_whenEventChangesDuringRetry_restartsCountAndBackoff() {
        PaymentSession session = submittedSession();
        for (int i = 1; i < PaymentSession.NOTIFY_MAX_ATTEMPTS; i++) {
            session.notifyFailed(HELD_EVENT, "HELD", true, TIME);
        }

        assertThat(session.notifyFailed(VOIDED_EVENT, "VOIDED", true, TIME)).isFalse();

        assertThat(session.getNotifyAttempts()).isEqualTo(1);
        assertThat(session.getNotifyEventId()).isEqualTo(VOIDED_EVENT);
        assertThat(session.getNextNotifyAt()).isEqualTo(TIME.plusSeconds(5));
        assertThat(session.getNotifiedStatus()).isNull();
    }

    // 그만둔 상태는 다시 예약하지 않고, 상태가 바뀌면 횟수를 새로 세어 다시 알린다
    @Test
    void scheduleNotifyIfChanged_afterGivingUp_onlyReschedulesNewStatus() {
        PaymentSession session = submittedSession();
        for (int i = 0; i < PaymentSession.NOTIFY_MAX_ATTEMPTS; i++) {
            session.notifyFailed(HELD_EVENT, "HELD", true, TIME);
        }

        session.scheduleNotifyIfChanged("HELD", true, TIME.plusSeconds(1));
        assertThat(session.getNextNotifyAt()).isNull();

        session.scheduleNotifyIfChanged("VOIDED", true, TIME.plusSeconds(2));
        assertThat(session.getNextNotifyAt()).isEqualTo(TIME.plusSeconds(2));
        assertThat(session.getNotifyAttempts()).isZero();
    }

    // 보내는 데 성공하면 재시도 중이던 eventId를 지운다
    @Test
    void notified_clearsRetryingEvent() {
        PaymentSession session = submittedSession();
        session.notifyFailed(HELD_EVENT, "HELD", true, TIME);

        session.notified("HELD", true);

        assertThat(session.getNotifyEventId()).isNull();
        assertThat(session.getNotifyAttempts()).isZero();
        assertThat(session.getNextNotifyAt()).isNull();
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
