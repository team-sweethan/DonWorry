package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.identity.api.TenantReader;
import com.donworry.payment.dto.MerchantPaymentResponse;
import com.donworry.payment.dto.PaymentSessionCreateRequest;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class PaymentSessionServiceTests {
    private static final long MERCHANT_ID = 7L;
    private static final String KEY = "6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String SUCCESS = "http://shop.test/orders/1/payment/donworry/return";
    private static final String FAIL = "http://shop.test/orders/1/payment/fail";

    private final PaymentSessionRepository sessions = mock(PaymentSessionRepository.class);
    private final PaymentRequestFlowService flow = mock(PaymentRequestFlowService.class);
    private final MerchantPaymentQueryService query = mock(MerchantPaymentQueryService.class);
    private final PaymentSessionService service = new PaymentSessionService(
            sessions,
            flow,
            query,
            mock(TenantReader.class),
            mock(PlatformTransactionManager.class),
            "http://pay.test/");

    @Test
    void create_savesSessionAndReturnsHostedCheckoutUrl() {
        when(sessions.findByMerchantIdAndRequestKey(MERCHANT_ID, UUID.fromString(KEY)))
                .thenReturn(Optional.empty());
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(MERCHANT_ID, KEY, request(recent(), SUCCESS));

        assertThat(response.sessionKey()).matches("pss_[0-9a-f]{32}");
        assertThat(response.checkoutUrl()).isEqualTo("http://pay.test/checkout/" + response.sessionKey());
    }

    @Test
    void create_invalidKeyOrDifferentReturnOrigin_isBadRequest() {
        assertThatThrownBy(() -> service.create(MERCHANT_ID, "not-a-uuid", request(recent(), SUCCESS)))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.create(
                        MERCHANT_ID, KEY, request(recent(), "http://other.test/orders/1/payment/donworry/return")))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        verify(sessions, never()).save(any());
    }

    @Test
    void create_afterPaymentWindow_isOrderExpired() {
        when(sessions.findByMerchantIdAndRequestKey(MERCHANT_ID, UUID.fromString(KEY)))
                .thenReturn(Optional.empty());
        Instant old = Instant.now().minus(11, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        assertThatThrownBy(() -> service.create(MERCHANT_ID, KEY, request(old, SUCCESS)))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class, e -> assertThat(e.getCode()).isEqualTo("ORDER_EXPIRED"));
    }

    @Test
    void create_sameKeyDifferentAmount_isConflict() {
        Instant created = recent();
        when(sessions.findByMerchantIdAndRequestKey(MERCHANT_ID, UUID.fromString(KEY)))
                .thenReturn(Optional.of(session(created)));
        var changed =
                new PaymentSessionCreateRequest("ORD-1", "42", 99_000L, created, "딸기 케이크", null, SUCCESS, FAIL, null);

        assertThatThrownBy(() -> service.create(MERCHANT_ID, KEY, changed))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void result_authorizedHeldPayment_returnsToSuccessUrl() {
        var session = session(recent());
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.HELD, true, null)));

        var result = service.result(sessionWithPayment(session));

        assertThat(result.authorized()).isTrue();
        assertThat(result.redirectUrl()).isEqualTo(SUCCESS + "?paymentKey=101&orderId=ORD-1&amount=52000");
    }

    @Test
    void result_pendingBeforeAuthorization_isInProgress() {
        var session = session(recent());
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.PENDING, false, null)));

        var result = service.result(sessionWithPayment(session));

        assertThat(result.inProgress()).isTrue();
        assertThat(result.redirectUrl()).isNull();
    }

    @Test
    void result_declined_returnsToFailUrlWithCode() {
        var session = session(recent());
        when(query.findPayment(MERCHANT_ID, 101L))
                .thenReturn(Optional.of(payment(PaymentStatus.DENIED, false, "FDS_DENIED")));

        var result = service.result(sessionWithPayment(session));

        assertThat(result.redirectUrl()).isEqualTo(FAIL + "?code=FDS_DENIED");
    }

    @Test
    void open_unknownSessionKey_isNotFound() {
        assertThatThrownBy(() -> service.open("pss_bad"))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    private static Instant recent() {
        return Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    }

    private static PaymentSessionCreateRequest request(Instant created, String successUrl) {
        return new PaymentSessionCreateRequest("ORD-1", "42", 52_000L, created, "딸기 케이크", null, successUrl, FAIL, null);
    }

    private static PaymentSession session(Instant created) {
        return PaymentSession.builder()
                .sessionKey("pss_" + "a".repeat(32))
                .merchantId(MERCHANT_ID)
                .requestKey(UUID.fromString(KEY))
                .attemptKey(UUID.randomUUID())
                .orderRef("ORD-1")
                .customerRef("42")
                .amount(52_000L)
                .orderCreatedAt(created)
                .orderName("딸기 케이크")
                .successUrl(SUCCESS)
                .failUrl(FAIL)
                .expiresAt(created.plus(10, ChronoUnit.MINUTES))
                .createdAt(created)
                .build();
    }

    private static PaymentSession sessionWithPayment(PaymentSession session) {
        session.submitted(101L, Instant.now());
        return session;
    }

    private static MerchantPaymentResponse payment(PaymentStatus status, boolean authorized, String declineCode) {
        return new MerchantPaymentResponse(
                101L, "ORD-1", 1L, status, Instant.now(), Instant.now(), 52_000L, declineCode, authorized);
    }
}
