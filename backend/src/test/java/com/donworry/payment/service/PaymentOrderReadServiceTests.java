package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.donworry.payment.api.PaymentOrderSummary;
import com.donworry.payment.entity.PaymentOrderContact;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.repository.PaymentOrderContactRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// AI 조사 주문 요약 (#634)
class PaymentOrderReadServiceTests {
    private static final long MERCHANT_ID = 7L;
    private static final Instant ORDERED_AT = Instant.parse("2026-11-07T00:00:00Z");

    private final PaymentSessionRepository sessions = mock(PaymentSessionRepository.class);
    private final PaymentOrderContactRepository contacts = mock(PaymentOrderContactRepository.class);
    private final PaymentOrderReadService service = new PaymentOrderReadService(sessions, contacts);

    // 주문자 전화 지문이 같은 결제는 같은 묶음 번호, 다르면 다른 번호를 받는다
    @Test
    void findOrders_whenSamePhoneFingerprint_assignsSameGroup() {
        // 목 세션은 바깥 when(...) 밖에서 먼저 만든다 — 안에서 만들면 Mockito 스텁이 겹친다
        List<PaymentSession> found = List.of(session(11L, 1L), session(12L, 2L), session(13L, 3L), session(14L, 4L));
        when(sessions.findByPaymentIdIn(anyCollection())).thenReturn(found);
        when(contacts.findBySessionIdIn(anyCollection()))
                .thenReturn(List.of(
                        contact(1L, "v1", "hmac-a"),
                        contact(2L, "v1", "hmac-b"),
                        contact(3L, "v1", "hmac-a"),
                        // 같은 HMAC 값이라도 키 버전이 다르면 같은 번호라고 볼 수 없다
                        contact(4L, "v2", "hmac-a")));

        List<PaymentOrderSummary> orders = service.findOrders(List.of(11L, 12L, 13L, 14L));

        assertThat(orders)
                .extracting(PaymentOrderSummary::paymentId, PaymentOrderSummary::ordererPhoneGroup)
                .containsExactly(tuple(11L, 1), tuple(12L, 2), tuple(13L, 1), tuple(14L, 3));
    }

    // 결제 준비 세션이 없는 결제는 "주문 정보 없음"으로 둔다
    @Test
    void findOrders_whenNoSession_marksNotProvided() {
        List<PaymentSession> found = List.of(session(11L, 1L));
        when(sessions.findByPaymentIdIn(anyCollection())).thenReturn(found);
        when(contacts.findBySessionIdIn(anyCollection())).thenReturn(List.of());

        List<PaymentOrderSummary> orders = service.findOrders(List.of(11L, 12L));

        assertThat(orders).hasSize(2);
        PaymentOrderSummary withSession = orders.get(0);
        assertThat(withSession.provided()).isTrue();
        assertThat(withSession.orderRef()).isEqualTo("order-11");
        // 연락처 행이 없으면 주문자를 받지 않은 것이다 — 픽업자 "다름"으로 적지 않는다
        assertThat(withSession.ordererProvided()).isFalse();
        assertThat(withSession.ordererPhoneGroup()).isNull();
        assertThat(withSession.pickupDiffers()).isNull();
        PaymentOrderSummary withoutSession = orders.get(1);
        assertThat(withoutSession.paymentId()).isEqualTo(12L);
        assertThat(withoutSession.provided()).isFalse();
        assertThat(withoutSession.orderRef()).isNull();
        assertThat(withoutSession.pickupDiffers()).isNull();
    }

    private static PaymentSession session(Long paymentId, Long sessionId) {
        PaymentSession session = mock(PaymentSession.class);
        when(session.getPaymentId()).thenReturn(paymentId);
        when(session.getSessionId()).thenReturn(sessionId);
        when(session.getOrderRef()).thenReturn("order-" + paymentId);
        when(session.getOrderName()).thenReturn("딸기 케이크");
        when(session.getOrderCreatedAt()).thenReturn(ORDERED_AT);
        return session;
    }

    private static PaymentOrderContact contact(Long sessionId, String keyId, String phoneHmac) {
        return PaymentOrderContact.of(
                sessionId, MERCHANT_ID, "name-enc", "phone-enc", phoneHmac, false, "digest", keyId, ORDERED_AT);
    }
}
