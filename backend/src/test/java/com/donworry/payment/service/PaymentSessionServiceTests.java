package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.identity.api.TenantReader;
import com.donworry.identity.api.TenantSummary;
import com.donworry.payment.dto.CheckoutResponses;
import com.donworry.payment.dto.MerchantPaymentResponse;
import com.donworry.payment.dto.PaymentSessionCreateRequest;
import com.donworry.payment.entity.PaymentOrderContact;
import com.donworry.payment.entity.PaymentRequest;
import com.donworry.payment.entity.PaymentRiskSignals;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentOrderContactRepository;
import com.donworry.payment.repository.PaymentRequestRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class PaymentSessionServiceTests {
    private static final long MERCHANT_ID = 7L;
    private static final String KEY = "6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String SUCCESS = "http://shop.test/orders/1/payment/donworry/return";
    private static final String FAIL = "http://shop.test/orders/1/payment/fail";

    private final PaymentSessionRepository sessions = mock(PaymentSessionRepository.class);
    private final PaymentRequestRepository requests = mock(PaymentRequestRepository.class);
    private final PaymentRequestFlowService flow = mock(PaymentRequestFlowService.class);
    private final MerchantPaymentQueryService query = mock(MerchantPaymentQueryService.class);
    private final TenantReader tenants = mock(TenantReader.class);
    private final PaymentOrderContactRepository contacts = mock(PaymentOrderContactRepository.class);
    private final CustomerContactProtector protector = new CustomerContactProtector(
            CustomerContactProtectorTests.ENCRYPTION_KEY, CustomerContactProtectorTests.HMAC_KEY);
    private final PaymentSessionService service = service(protector);

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
        var changed = requestBuilder(created).amount(99_000L).build();

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

    // 가맹점 주소의 기존 인코딩(%2F)은 그대로 두고, 새로 붙이는 값만 인코딩한다 (#425 리뷰)
    @Test
    void result_registeredUrlWithEncodedQuery_keepsEncodingAndEncodesNewValues() {
        String success = SUCCESS + "?next=%2Forders";
        String fail = FAIL + "?next=%2Fcart";
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.HELD, true, null)));

        var ok = service.result(sessionWithPayment(session(recent(), success, fail, "ORD+1 &x=2")));

        assertThat(ok.redirectUrl()).isEqualTo(success + "&paymentKey=101&orderId=ORD%2B1%20%26x%3D2&amount=52000");

        when(query.findPayment(MERCHANT_ID, 101L))
                .thenReturn(Optional.of(payment(PaymentStatus.DENIED, false, "FDS_DENIED")));
        var denied = service.result(sessionWithPayment(session(recent(), success, fail, "ORD-1")));

        assertThat(denied.redirectUrl()).isEqualTo(fail + "&code=FDS_DENIED");
    }

    // 나중에 값을 붙일 수 없는 주소(쿼리의 [ ])는 결제 때 500이 되지 않도록 생성 때 400으로 막는다
    @Test
    void create_returnUrlThatCannotTakeQuery_isBadRequest() {
        assertThatThrownBy(() -> service.create(MERCHANT_ID, KEY, request(recent(), SUCCESS + "?a=[1]")))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        verify(sessions, never()).save(any());
    }

    @Test
    void open_unknownSessionKey_isNotFound() {
        assertThatThrownBy(() -> service.open("pss_bad"))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // 주문자 · 픽업자 원문은 DB에 남지 않는다 — 암호문 · 전화 지문 · "주문자와 다름"만 저장한다 (#602)
    @Test
    void create_withContact_storesNoRawNameOrPhone() {
        newSessionSavedWithId(31L);

        service.create(MERCHANT_ID, KEY, withContact(recent()));

        var saved = ArgumentCaptor.forClass(PaymentOrderContact.class);
        verify(contacts).save(saved.capture());
        var row = saved.getValue();
        assertThat(row.getSessionId()).isEqualTo(31L);
        assertThat(row.getPickupDiffers()).isTrue();
        var stored = String.join(
                "|",
                row.getOrdererNameEnc(),
                row.getOrdererPhoneEnc(),
                row.getOrdererPhoneHmac(),
                row.getRequestDigest());
        for (var raw : new String[] {"김민수", "이영희", "01012345678", "010-1234-5678", "01098765432", "5678"}) {
            assertThat(stored).as(raw).doesNotContain(raw);
        }
    }

    // 키 설정이 없어도 결제 준비는 성공한다. 연락처만 저장하지 않는다
    @Test
    void create_withoutKeys_succeedsWithoutContact() {
        newSessionSavedWithId(32L);
        var withoutKeys = service(new CustomerContactProtector("", ""));

        var response = withoutKeys.create(MERCHANT_ID, KEY, withContact(recent()));

        assertThat(response.sessionKey()).matches("pss_[0-9a-f]{32}");
        verify(contacts, never()).save(any());
    }

    // 같은 요청 키로 연락처만 바꿔 보내면 저장된 주문자와 어긋나므로 409로 막는다
    @Test
    void create_sameKeyDifferentContact_returns409() {
        Instant created = recent();
        storedSessionWithContact(created);
        var changed = requestBuilder(created)
                .ordererName("김민수")
                .ordererPhone("010-1111-2222")
                .pickupName("이영희")
                .pickupPhone("010-9876-5432")
                .build();

        assertThatThrownBy(() -> service.create(MERCHANT_ID, KEY, changed))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
    }

    // 가맹점이 같은 내용으로 재시도하면(하이픈 · 공백 차이 포함) 같은 세션을 돌려준다
    @Test
    void create_sameKeySameContact_returnsSameSession() {
        Instant created = recent();
        var session = storedSessionWithContact(created);
        var retried = requestBuilder(created)
                .ordererName(" 김민수 ")
                .ordererPhone("01012345678")
                .pickupName("이영희")
                .pickupPhone("010 9876 5432")
                .build();

        var response = service.create(MERCHANT_ID, KEY, retried);

        assertThat(response.sessionKey()).isEqualTo(session.getSessionKey());
        verify(sessions, never()).save(any());
    }

    // 형식이 틀린 주문자 번호는 세션에 고정되기 전에 400으로 막는다
    @Test
    void create_invalidOrdererPhone_returns400() {
        for (var phone : new String[] {"+82-10-1234-5678", "1234", "010-1234-567a", ""}) {
            var body = requestBuilder(recent()).ordererPhone(phone).build();
            assertThatThrownBy(() -> service.create(MERCHANT_ID, KEY, body))
                    .as(phone)
                    .isInstanceOfSatisfying(
                            PaymentHttpException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(400));
        }
        verify(sessions, never()).save(any());
    }

    // 요청 로그에 이메일 · 이름 · 전화가 남지 않는다
    @Test
    void toString_excludesContacts() {
        var text = requestBuilder(recent())
                .buyerEmail("buyer@example.test")
                .ordererName("김민수")
                .ordererPhone("010-1234-5678")
                .pickupName("이영희")
                .pickupPhone("010-9876-5432")
                .build()
                .toString();

        assertThat(text).doesNotContain("buyer@example.test", "김민수", "1234-5678", "이영희", "9876");
    }

    // 구매자 이메일은 세션에 고정되므로 FDS 입력과 같은 규칙으로 만들 때 거절한다 (PR #425 리뷰)
    @Test
    void create_invalidBuyerEmail_isBadRequest() {
        for (String email : new String[] {"", "not-an-email", " buyer@example.test"}) {
            var body = requestBuilder(recent()).buyerEmail(email).build();
            assertThatThrownBy(() -> service.create(MERCHANT_ID, KEY, body))
                    .as(email)
                    .isInstanceOfSatisfying(
                            PaymentHttpException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(400));
        }
        verify(sessions, never()).save(any());
    }

    // 운영에서는 웹훅 주소(notifyUrl)로 https · 공인 주소만 받는다 — 내부 주소로 요청을 보내게 하지 않는다 (#402 PR②)
    @Test
    void create_notifyUrlHttpOrInternalAddress_isBadRequest() {
        for (String notifyUrl : new String[] {
            "http://shop.test/hooks/donworry", "https://127.0.0.1/hook", "https://10.1.2.3/hook", "https://[::1]/hook"
        }) {
            var body = requestBuilder(recent()).notifyUrl(notifyUrl).build();
            assertThatThrownBy(() -> service.create(MERCHANT_ID, KEY, body))
                    .as(notifyUrl)
                    .isInstanceOfSatisfying(
                            PaymentHttpException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(400));
        }
        verify(sessions, never()).save(any());
    }

    // 동작하지 않던 C(한도 초과) · D(해외 발급) 카드는 목록에서 뺐다. 그 코드로 결제하면 시도를 고정하지 않고 400 (#582)
    @Test
    void pay_removedCardCode_isBadRequestWithoutFixingAttempt() {
        var session = session(recent());
        when(sessions.findBySessionKey(session.getSessionKey())).thenReturn(Optional.of(session));

        for (var code : new String[] {"C", "D"}) {
            assertThatThrownBy(() -> service.pay(
                            session.getSessionKey(),
                            new CheckoutResponses.PayRequest(code, "abcdef12"),
                            "203.0.113.10"))
                    .as(code)
                    .isInstanceOfSatisfying(
                            PaymentHttpException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(400));
        }
        verify(flow, never()).submit(any());
        assertThat(session.attemptFixed()).isFalse();
        assertThat(CheckoutMockCard.values())
                .extracting(CheckoutMockCard::label)
                .containsExactly("한빛카드", "누리카드");
    }

    // 세션을 만든 뒤 가맹점이 정지되면 아직 접수하지 않은 결제를 시작하지 않는다 (PR #425 리뷰)
    @Test
    void pay_merchantInactive_isConflictWithoutSubmitting() {
        var session = session(recent());
        when(sessions.findBySessionKey(session.getSessionKey())).thenReturn(Optional.of(session));
        when(tenants.findById(MERCHANT_ID))
                .thenReturn(Optional.of(new TenantSummary(MERCHANT_ID, "CakeShop", "INACTIVE", BigDecimal.ONE)));

        assertThatThrownBy(() -> service.pay(
                        session.getSessionKey(), new CheckoutResponses.PayRequest("A", "abcdef12"), "203.0.113.10"))
                .isInstanceOfSatisfying(PaymentHttpException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("MERCHANT_INACTIVE");
                });
        verify(flow, never()).submit(any());
        assertThat(session.attemptFixed()).isFalse();
    }

    // 접수 뒤 평가·승인 중 실패해도, 다시 누르면 처음 카드·기기·IP로 같은 결제 시도를 이어 간다 (PR #425 리뷰)
    @Test
    void pay_retryAfterFailure_reusesFirstCardDeviceAndIp() {
        var session = session(recent());
        when(sessions.findBySessionKey(session.getSessionKey())).thenReturn(Optional.of(session));
        when(sessions.findByIdForUpdate(any())).thenReturn(Optional.of(session));
        when(tenants.findById(MERCHANT_ID))
                .thenReturn(Optional.of(new TenantSummary(MERCHANT_ID, "CakeShop", "ACTIVE", BigDecimal.ONE)));
        when(flow.submit(any()))
                .thenThrow(new IllegalStateException("evaluation failed"))
                .thenReturn(new PaymentRequestAdmission(101L, 1L, UUID.randomUUID(), false));
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.HELD, true, null)));

        assertThatThrownBy(() -> service.pay(
                        session.getSessionKey(), new CheckoutResponses.PayRequest("A", "abcdef12"), "203.0.113.10"))
                .isInstanceOf(IllegalStateException.class);
        var result =
                service.pay(session.getSessionKey(), new CheckoutResponses.PayRequest("B", "99999999"), "198.51.100.7");

        assertThat(result.redirectUrl()).startsWith(SUCCESS);
        var commands = ArgumentCaptor.forClass(PaymentRequestCommand.class);
        verify(flow, times(2)).submit(commands.capture());
        assertThat(commands.getAllValues()).allSatisfy(command -> {
            assertThat(command.idempotencyKey()).isEqualTo(session.getAttemptKey());
            assertThat(command.riskSignals().cardDisplayLabel()).isEqualTo("한빛카드 **** 4821");
            assertThat(command.riskSignals().deviceId()).isEqualTo("abcdef12");
            assertThat(command.riskSignals().ipAddress()).isEqualTo("203.0.113.10");
        });
        assertThat(session.getPaymentId()).isEqualTo(101L);
    }

    // 만료는 새 접수만 막는다. 아무것도 접수하지 않은 세션은 만료되면 409 (PR #425 리뷰)
    @Test
    void pay_expiredWithoutAdmission_isSessionExpired() {
        var session = session(expiredCreatedAt());
        when(sessions.findBySessionKey(session.getSessionKey())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.pay(
                        session.getSessionKey(), new CheckoutResponses.PayRequest("A", "abcdef12"), "203.0.113.10"))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class, e -> assertThat(e.getCode()).isEqualTo("SESSION_EXPIRED"));
        verify(flow, never()).submit(any());
    }

    // 만료 직전에 접수된 결제가 평가 · 승인 · 연결 중에 멈췄으면, 만료 · 가맹점 정지와 관계없이 같은 시도를 이어 간다
    @Test
    void pay_expiredButAlreadyAdmitted_resumesSameAttempt() {
        var session = session(expiredCreatedAt());
        session.fixAttempt("A", "abcdef12", "203.0.113.10", Instant.now());
        when(sessions.findBySessionKey(session.getSessionKey())).thenReturn(Optional.of(session));
        when(sessions.findByIdForUpdate(any())).thenReturn(Optional.of(session));
        admitted(session, 101L);
        when(tenants.findById(MERCHANT_ID))
                .thenReturn(Optional.of(new TenantSummary(MERCHANT_ID, "CakeShop", "INACTIVE", BigDecimal.ONE)));
        when(flow.submit(any())).thenReturn(new PaymentRequestAdmission(101L, 1L, UUID.randomUUID(), false));
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.HELD, true, null)));

        var result =
                service.pay(session.getSessionKey(), new CheckoutResponses.PayRequest("A", "abcdef12"), "203.0.113.10");

        assertThat(result.redirectUrl()).startsWith(SUCCESS);
        assertThat(session.getPaymentId()).isEqualTo(101L);
        var command = ArgumentCaptor.forClass(PaymentRequestCommand.class);
        verify(flow).submit(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo(session.getAttemptKey());
    }

    // 카드 이름을 바꾸기 전(#582)에 접수돼 멈춘 결제는 저장된 위험 신호 그대로 이어 간다.
    // 새 이름으로 다시 만들면 같은 시도 키인데 표시값이 달라 키 충돌로 거절된다. 지금은 없는 C 카드여도 같다 (PR #587 리뷰)
    // 배포 전에 열어 둔 결제창은 C를 고른 채 다시 보내므로, 이미 접수된 시도는 카드 목록 검사보다 먼저 재개한다
    @Test
    void pay_admittedBeforeCardRename_resumesWithStoredRiskSignals() {
        var session = session(recent());
        session.fixAttempt("C", "abcdef12", "203.0.113.10", Instant.now());
        when(sessions.findBySessionKey(session.getSessionKey())).thenReturn(Optional.of(session));
        when(sessions.findByIdForUpdate(any())).thenReturn(Optional.of(session));
        var stored = new PaymentRiskSignals(
                "abcdef12", "dwmock_stored", "모의 카드 C **** 9999", null, "203.0.113.10", "buyer@example.com");
        admitted(session, 101L, stored);
        when(flow.submit(any())).thenReturn(new PaymentRequestAdmission(101L, 1L, UUID.randomUUID(), false));
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.HELD, true, null)));

        var result =
                service.pay(session.getSessionKey(), new CheckoutResponses.PayRequest("C", "abcdef12"), "203.0.113.10");

        assertThat(result.redirectUrl()).startsWith(SUCCESS);
        var command = ArgumentCaptor.forClass(PaymentRequestCommand.class);
        verify(flow).submit(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo(session.getAttemptKey());
        assertThat(command.getValue().riskSignals()).isEqualTo(stored);
        assertThat(session.getPaymentId()).isEqualTo(101L);
    }

    // 지금은 없는 카드(C)로 고정만 되고 접수 전에 멈춘 세션은, 다시 고른 카드로 바꿔 접수한다.
    // 기기 · IP는 처음 값 그대로. 바꾸지 않으면 만료까지 계속 400이다 (PR #587 리뷰)
    @Test
    void pay_fixedWithRemovedCardButNotAdmitted_replacesCardAndSubmits() {
        var session = session(recent());
        session.fixAttempt("C", "abcdef12", "203.0.113.10", Instant.now());
        when(sessions.findBySessionKey(session.getSessionKey())).thenReturn(Optional.of(session));
        when(sessions.findByIdForUpdate(any())).thenReturn(Optional.of(session));
        when(tenants.findById(MERCHANT_ID))
                .thenReturn(Optional.of(new TenantSummary(MERCHANT_ID, "CakeShop", "ACTIVE", BigDecimal.ONE)));
        when(flow.submit(any())).thenReturn(new PaymentRequestAdmission(101L, 1L, UUID.randomUUID(), false));
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.HELD, true, null)));

        var result =
                service.pay(session.getSessionKey(), new CheckoutResponses.PayRequest("A", "99999999"), "198.51.100.7");

        assertThat(result.redirectUrl()).startsWith(SUCCESS);
        var command = ArgumentCaptor.forClass(PaymentRequestCommand.class);
        verify(flow).submit(command.capture());
        assertThat(command.getValue().riskSignals().cardDisplayLabel()).isEqualTo("한빛카드 **** 4821");
        assertThat(command.getValue().riskSignals().deviceId()).isEqualTo("abcdef12");
        assertThat(command.getValue().riskSignals().ipAddress()).isEqualTo("203.0.113.10");
        assertThat(session.getSubmittedCard()).isEqualTo("A");
    }

    // 접수됐지만 세션에 연결되기 전에 멈춘 결제도 결과 조회 · 결제창 열기에서 찾는다
    @Test
    void resultAndView_admittedButNotLinked_findPaymentByAttempt() {
        var session = session(expiredCreatedAt());
        when(sessions.findBySessionKey(session.getSessionKey())).thenReturn(Optional.of(session));
        admitted(session, 101L);
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.HELD, true, null)));

        assertThat(service.result(session.getSessionKey()).redirectUrl()).startsWith(SUCCESS);
        var view = service.open(session.getSessionKey());
        assertThat(view.expired()).isFalse();
        assertThat(view.result().redirectUrl()).startsWith(SUCCESS);

        // 처리 중에 멈춘 결제(PENDING)는 결과 대신 결제 버튼을 보여 다시 누르면 이어 가게 한다
        when(query.findPayment(MERCHANT_ID, 101L)).thenReturn(Optional.of(payment(PaymentStatus.PENDING, false, null)));
        var stuck = service.open(session.getSessionKey());
        assertThat(stuck.expired()).isFalse();
        assertThat(stuck.result()).isNull();
    }

    private void admitted(PaymentSession session, long paymentId) {
        admitted(session, paymentId, PaymentRiskSignals.orNone(null));
    }

    private void admitted(PaymentSession session, long paymentId, PaymentRiskSignals stored) {
        var request = mock(PaymentRequest.class);
        when(request.getPaymentId()).thenReturn(paymentId);
        when(request.riskSignals()).thenReturn(stored);
        when(requests.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, session.getAttemptKey()))
                .thenReturn(Optional.of(request));
    }

    private static Instant expiredCreatedAt() {
        return Instant.now().minus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant recent() {
        return Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    }

    private static PaymentSessionCreateRequest request(Instant created, String successUrl) {
        return requestBuilder(created).successUrl(successUrl).build();
    }

    private static PaymentSessionCreateRequest.PaymentSessionCreateRequestBuilder requestBuilder(Instant created) {
        return PaymentSessionCreateRequest.builder()
                .orderRef("ORD-1")
                .customerRef("42")
                .amount(52_000L)
                .orderCreatedAt(created)
                .orderName("딸기 케이크")
                .successUrl(SUCCESS)
                .failUrl(FAIL);
    }

    private static PaymentSessionCreateRequest withContact(Instant created) {
        return requestBuilder(created)
                .ordererName("김민수")
                .ordererPhone("010-1234-5678")
                .pickupName("이영희")
                .pickupPhone("010-9876-5432")
                .build();
    }

    private PaymentSessionService service(CustomerContactProtector contactProtector) {
        return new PaymentSessionService(
                sessions,
                requests,
                flow,
                query,
                tenants,
                new PaymentWebhookTargetPolicy(false),
                mock(PlatformTransactionManager.class),
                "http://pay.test/",
                contacts,
                contactProtector);
    }

    private void newSessionSavedWithId(long sessionId) {
        when(sessions.findByMerchantIdAndRequestKey(MERCHANT_ID, UUID.fromString(KEY)))
                .thenReturn(Optional.empty());
        when(sessions.save(any())).thenAnswer(invocation -> {
            PaymentSession saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "sessionId", sessionId);
            return saved;
        });
    }

    private static PaymentSession session(Instant created) {
        return session(created, SUCCESS, FAIL, "ORD-1");
    }

    // 원래 요청(withContact)으로 저장된 세션과 연락처
    private PaymentSession storedSessionWithContact(Instant created) {
        var session = session(created);
        ReflectionTestUtils.setField(session, "sessionId", 33L);
        when(sessions.findByMerchantIdAndRequestKey(MERCHANT_ID, UUID.fromString(KEY)))
                .thenReturn(Optional.of(session));
        var original = withContact(created);
        var stored = protector
                .protect(
                        MERCHANT_ID,
                        CustomerContactProtector.normalize(
                                original.ordererName(),
                                original.ordererPhone(),
                                original.pickupName(),
                                original.pickupPhone()))
                .orElseThrow();
        when(contacts.findById(33L))
                .thenReturn(Optional.of(PaymentOrderContact.of(
                        33L,
                        MERCHANT_ID,
                        stored.ordererNameEnc(),
                        stored.ordererPhoneEnc(),
                        stored.ordererPhoneHmac(),
                        stored.pickupDiffers(),
                        stored.requestDigest(),
                        stored.keyId(),
                        created)));
        return session;
    }

    private static PaymentSession session(Instant created, String successUrl, String failUrl, String orderRef) {
        return PaymentSession.builder()
                .sessionKey("pss_" + "a".repeat(32))
                .merchantId(MERCHANT_ID)
                .requestKey(UUID.fromString(KEY))
                .attemptKey(UUID.randomUUID())
                .orderRef(orderRef)
                .customerRef("42")
                .amount(52_000L)
                .orderCreatedAt(created)
                .orderName("딸기 케이크")
                .successUrl(successUrl)
                .failUrl(failUrl)
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
