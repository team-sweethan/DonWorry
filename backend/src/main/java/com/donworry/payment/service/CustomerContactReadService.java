package com.donworry.payment.service;

import com.donworry.payment.api.ContactMatchResult;
import com.donworry.payment.api.CustomerContactReader;
import com.donworry.payment.api.MaskedContact;
import com.donworry.payment.entity.PaymentOrderContact;
import com.donworry.payment.repository.PaymentOrderContactRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 주문자 정보 읽기 (#619) — 결제 → 결제 준비 세션 → 연락처. 복호화 AAD는 저장할 때와 같은 세션 가맹점 ID
// 트랜잭션은 호출 쪽(사건 서비스)이 연다 — 접근 기록과 같은 트랜잭션이어야 해서 여기서 읽기 전용으로 열지 않는다
@Service
@RequiredArgsConstructor
public class CustomerContactReadService implements CustomerContactReader {
    private final PaymentSessionRepository sessions;
    private final PaymentOrderContactRepository contacts;
    private final CustomerContactProtector protector;

    @Override
    public Optional<MaskedContact> masked(Long paymentId) {
        return contact(paymentId)
                .map(contact -> new MaskedContact(
                        CustomerContactMasking.name(decrypt(contact, CustomerContactProtector.ORDERER_NAME)),
                        CustomerContactMasking.phone(decrypt(contact, CustomerContactProtector.ORDERER_PHONE)),
                        contact.getPickupDiffers()));
    }

    @Override
    public ContactMatchResult matchOrdererPhone(Long paymentId, String phone) {
        // 형식 검사를 먼저 해서, 연락처가 있는지 없는지와 상관없이 틀린 입력은 같은 오류가 된다
        String digits =
                CustomerContactProtector.normalize(null, phone, null, null).ordererPhone();
        if (digits == null) throw new IllegalArgumentException("phone");
        Optional<String> stored = contact(paymentId).map(PaymentOrderContact::getOrdererPhoneHmac);
        if (stored.isEmpty()) return ContactMatchResult.NO_CONTACT;
        boolean matched = MessageDigest.isEqual(
                stored.get().getBytes(StandardCharsets.US_ASCII),
                protector.phoneFingerprint(digits).getBytes(StandardCharsets.US_ASCII));
        return matched ? ContactMatchResult.MATCHED : ContactMatchResult.NOT_MATCHED;
    }

    @Override
    public Optional<String> ordererPhone(Long paymentId) {
        return contact(paymentId)
                .map(contact -> decrypt(contact, CustomerContactProtector.ORDERER_PHONE))
                .map(CustomerContactMasking::formatPhone);
    }

    // 키가 없으면 복호화 · 지문 계산을 못 하므로 연락처가 없는 것으로 본다
    private Optional<PaymentOrderContact> contact(Long paymentId) {
        if (!protector.available()) return Optional.empty();
        return sessions.findByPaymentId(paymentId).flatMap(session -> contacts.findById(session.getSessionId()));
    }

    private String decrypt(PaymentOrderContact contact, String field) {
        String stored = CustomerContactProtector.ORDERER_NAME.equals(field)
                ? contact.getOrdererNameEnc()
                : contact.getOrdererPhoneEnc();
        return protector.decrypt(contact.getMerchantId(), field, stored);
    }
}
