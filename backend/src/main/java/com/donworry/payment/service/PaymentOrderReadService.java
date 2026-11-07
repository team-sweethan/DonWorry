package com.donworry.payment.service;

import com.donworry.payment.api.PaymentOrderReader;
import com.donworry.payment.api.PaymentOrderSummary;
import com.donworry.payment.entity.PaymentOrderContact;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.repository.PaymentOrderContactRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI 조사 주문 요약 (#634) — 결제 → 결제 준비 세션 → 주문자 연락처
// 암호문은 풀지 않는다. 같은 전화인지는 저장된 지문(키 버전 + HMAC)이 같은지로만 본다 — 지문은 이 클래스 밖으로 나가지 않는다
@Service
@RequiredArgsConstructor
public class PaymentOrderReadService implements PaymentOrderReader {
    // 사건 결제는 많아야 수십 건이다. 호출자 실수로 큰 IN 목록을 만들지 않게 막는다
    static final int MAX_PAYMENTS = 100;

    private final PaymentSessionRepository sessions;
    private final PaymentOrderContactRepository contacts;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentOrderSummary> findOrders(Collection<Long> paymentIds) {
        if (paymentIds == null) {
            throw new IllegalArgumentException("paymentIds must not be null");
        }
        if (paymentIds.size() > MAX_PAYMENTS) {
            throw new IllegalArgumentException("paymentIds must be at most " + MAX_PAYMENTS);
        }
        if (paymentIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("paymentIds must be positive");
        }
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        Map<Long, PaymentSession> sessionByPayment = sessions.findByPaymentIdIn(paymentIds).stream()
                .collect(Collectors.toMap(PaymentSession::getPaymentId, Function.identity()));
        List<Long> sessionIds = sessionByPayment.values().stream()
                .map(PaymentSession::getSessionId)
                .toList();
        Map<Long, PaymentOrderContact> contactBySession = sessionIds.isEmpty()
                ? Map.of()
                : contacts.findBySessionIdIn(sessionIds).stream()
                        .collect(Collectors.toMap(PaymentOrderContact::getSessionId, Function.identity()));

        // 넘긴 결제 순서대로 처음 보는 지문에 1, 2, 3…을 매긴다
        Map<String, Integer> phoneGroups = new HashMap<>();
        return paymentIds.stream()
                .map(paymentId -> {
                    PaymentSession session = sessionByPayment.get(paymentId);
                    if (session == null) {
                        return PaymentOrderSummary.notProvided(paymentId);
                    }
                    PaymentOrderContact contact = contactBySession.get(session.getSessionId());
                    return PaymentOrderSummary.builder()
                            .paymentId(paymentId)
                            .provided(true)
                            .orderRef(session.getOrderRef())
                            .orderName(session.getOrderName())
                            .orderCreatedAt(session.getOrderCreatedAt())
                            .ordererProvided(contact != null)
                            .ordererPhoneGroup(phoneGroup(contact, phoneGroups))
                            .pickupDiffers(contact == null ? null : contact.getPickupDiffers())
                            .build();
                })
                .toList();
    }

    private static Integer phoneGroup(PaymentOrderContact contact, Map<String, Integer> groups) {
        if (contact == null || contact.getOrdererPhoneHmac() == null) {
            return null;
        }
        // 키를 바꾸면 같은 번호도 지문이 달라진다 — 키 버전까지 같아야 같은 전화로 본다
        String fingerprint = contact.getKeyId() + ":" + contact.getOrdererPhoneHmac();
        return groups.computeIfAbsent(fingerprint, key -> groups.size() + 1);
    }
}
