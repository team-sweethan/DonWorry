package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.donworry.payment.api.ContactMatchResult;
import com.donworry.payment.entity.PaymentOrderContact;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.repository.PaymentOrderContactRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// 주문자 정보 대조 · 가리기 (#619)
class CustomerContactReadServiceTests {
    private static final long MERCHANT_ID = 7L;

    private final CustomerContactProtector protector = new CustomerContactProtector(
            CustomerContactProtectorTests.ENCRYPTION_KEY, CustomerContactProtectorTests.HMAC_KEY);
    private final PaymentSessionRepository sessions = mock(PaymentSessionRepository.class);
    private final PaymentOrderContactRepository contacts = mock(PaymentOrderContactRepository.class);
    private final CustomerContactReadService service = new CustomerContactReadService(sessions, contacts, protector);

    // 하이픈 · 공백 차이로 "불일치"가 나오면 본인 확인이 틀리고,
    // 다른 결제 주문자 번호와 비교되면 대조가 "이 번호의 고객 찾기" 도구가 된다
    @Test
    void contactMatch_normalizesHyphenAndUsesOnlyThisPayment() {
        stubContact(31L, 5L, "01012345678");
        stubContact(32L, 6L, "01099998888");

        assertThat(service.matchOrdererPhone(31L, "010-1234-5678")).isEqualTo(ContactMatchResult.MATCHED);
        assertThat(service.matchOrdererPhone(31L, "010 1234 5678")).isEqualTo(ContactMatchResult.MATCHED);
        assertThat(service.matchOrdererPhone(31L, "010-9999-8888")).isEqualTo(ContactMatchResult.NOT_MATCHED);
    }

    // 가린 값에 원문이 그대로 나가면 단계를 나눈 의미가 없다
    @Test
    void mask_ordererNameAndPhone() {
        assertThat(CustomerContactMasking.name("홍길동")).isEqualTo("홍*동");
        assertThat(CustomerContactMasking.name("남궁민수")).isEqualTo("남**수");
        assertThat(CustomerContactMasking.name("이훈")).isEqualTo("이*");
        assertThat(CustomerContactMasking.phone("01012345678")).isEqualTo("010-****-5678");
        assertThat(CustomerContactMasking.phone("0212345678")).isEqualTo("02-****-5678");

        stubContact(31L, 5L, "01012345678");
        var masked = service.masked(31L).orElseThrow();
        assertThat(masked.ordererName()).isEqualTo("홍*동");
        assertThat(masked.ordererPhone()).isEqualTo("010-****-5678");
    }

    private void stubContact(Long paymentId, Long sessionId, String phone) {
        PaymentSession session = mock(PaymentSession.class);
        when(session.getSessionId()).thenReturn(sessionId);
        when(sessions.findByPaymentId(paymentId)).thenReturn(Optional.of(session));
        var stored = protector
                .protect(MERCHANT_ID, CustomerContactProtector.normalize("홍길동", phone, null, null))
                .orElseThrow();
        when(contacts.findById(sessionId))
                .thenReturn(Optional.of(PaymentOrderContact.of(
                        sessionId,
                        MERCHANT_ID,
                        stored.ordererNameEnc(),
                        stored.ordererPhoneEnc(),
                        stored.ordererPhoneHmac(),
                        stored.pickupDiffers(),
                        stored.requestDigest(),
                        stored.keyId(),
                        Instant.parse("2026-11-07T00:00:00Z"))));
    }
}
