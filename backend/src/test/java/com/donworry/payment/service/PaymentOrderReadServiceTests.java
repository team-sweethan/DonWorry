package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.donworry.payment.api.PaymentOrderSummary;
import com.donworry.payment.entity.PaymentOrderContact;
import com.donworry.payment.entity.PaymentRequest;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.repository.PaymentOrderContactRepository;
import com.donworry.payment.repository.PaymentRequestRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

// AI 조사 주문 요약 (#634)
class PaymentOrderReadServiceTests {
    private static final long MERCHANT_ID = 7L;
    private static final Instant ORDERED_AT = Instant.parse("2026-11-07T00:00:00Z");

    private final PaymentSessionRepository sessions = mock(PaymentSessionRepository.class);
    private final PaymentOrderContactRepository contacts = mock(PaymentOrderContactRepository.class);
    private final PaymentRequestRepository requests = mock(PaymentRequestRepository.class);
    private final PaymentOrderReadService service = new PaymentOrderReadService(sessions, contacts, requests);

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

    // 결제창 결제가 세션에 연결되기 전에도 결제 시도 키로 주문을 찾는다 (#634 Codex 리뷰)
    // 결제창은 접수 · 승인(보류 → 사건 → 고정 보고서)이 끝난 뒤에 세션에 결제 ID를 저장한다
    @Test
    void findOrders_whenSessionNotYetLinked_findsByAttemptKey() {
        UUID attemptKey = UUID.fromString("00000000-0000-0000-0000-000000000011");
        PaymentRequest request = mock(PaymentRequest.class);
        when(request.getMerchantId()).thenReturn(MERCHANT_ID);
        when(request.getIdempotencyKey()).thenReturn(attemptKey);
        PaymentSession unlinked = session(null, 1L);
        when(sessions.findByPaymentIdIn(anyCollection())).thenReturn(List.of());
        when(requests.findByPaymentId(11L)).thenReturn(Optional.of(request));
        when(sessions.findByMerchantIdAndAttemptKey(MERCHANT_ID, attemptKey)).thenReturn(Optional.of(unlinked));
        when(contacts.findBySessionIdIn(anyCollection())).thenReturn(List.of(contact(1L, "v1", "hmac-a")));

        List<PaymentOrderSummary> orders = service.findOrders(List.of(11L));

        assertThat(orders).singleElement().satisfies(order -> {
            assertThat(order.paymentId()).isEqualTo(11L);
            assertThat(order.provided()).isTrue();
            assertThat(order.orderRef()).isEqualTo("order-null");
            assertThat(order.ordererProvided()).isTrue();
            assertThat(order.ordererPhoneGroup()).isEqualTo(1);
        });
    }

    // 100건을 넘는 사건도 나눠 읽고, 전화 묶음 번호는 전체 기준으로 매긴다 (#634 Codex 리뷰)
    // 사건 병합에는 건수 상한이 없다 — 상한에 걸려 예외가 나면 재시도해도 주문 정보를 못 읽는다
    @Test
    void findOrders_withMoreThanHundredPayments_groupsAcrossChunks() {
        List<Long> paymentIds = LongStream.rangeClosed(1, 150).boxed().toList();
        List<PaymentSession> all =
                paymentIds.stream().map(id -> session(id, id)).toList();
        when(sessions.findByPaymentIdIn(anyCollection())).thenAnswer(call -> {
            Collection<?> ids = call.getArgument(0);
            assertThat(ids).hasSizeLessThanOrEqualTo(100);
            return all.stream().filter(s -> ids.contains(s.getPaymentId())).toList();
        });
        when(contacts.findBySessionIdIn(anyCollection())).thenAnswer(call -> {
            Collection<?> ids = call.getArgument(0);
            assertThat(ids).hasSizeLessThanOrEqualTo(100);
            return ids.stream().map(id -> contact((Long) id, "v1", "hmac-a")).toList();
        });

        List<PaymentOrderSummary> orders = service.findOrders(paymentIds);

        assertThat(orders).hasSize(150).allSatisfy(order -> {
            assertThat(order.provided()).isTrue();
            // 첫 묶음과 둘째 묶음에서 읽은 같은 전화가 같은 번호를 받는다
            assertThat(order.ordererPhoneGroup()).isEqualTo(1);
        });
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
