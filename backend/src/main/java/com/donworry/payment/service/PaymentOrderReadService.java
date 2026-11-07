package com.donworry.payment.service;

import com.donworry.payment.api.PaymentOrderReader;
import com.donworry.payment.api.PaymentOrderSummary;
import com.donworry.payment.entity.PaymentOrderContact;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.repository.PaymentOrderContactRepository;
import com.donworry.payment.repository.PaymentRequestRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI 조사 주문 요약 (#634) — 결제 → 결제 준비 세션 → 주문자 연락처
// 결제창은 접수 · 승인(보류 이벤트 → 사건 → 보고서)이 끝난 뒤에 세션에 결제 ID를 저장한다 (PaymentSessionService.pay)
// 그 사이에 불려도 찾도록, 결제 ID로 못 찾은 결제는 접수 기록의 결제 시도 키(= 세션 attemptKey)로 세션을 찾는다
// 암호문은 풀지 않는다. 같은 전화인지는 저장된 지문(키 버전 + HMAC)이 같은지로만 본다 — 지문은 이 클래스 밖으로 나가지 않는다
@Service
@RequiredArgsConstructor
public class PaymentOrderReadService implements PaymentOrderReader {
    // 사건 병합에는 건수 상한이 없다 — 막지 않고 IN 목록을 이 크기로 나눠 읽는다 (#634 Codex 리뷰)
    static final int CHUNK = 100;

    private final PaymentSessionRepository sessions;
    private final PaymentOrderContactRepository contacts;
    private final PaymentRequestRepository requests;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentOrderSummary> findOrders(Collection<Long> paymentIds) {
        if (paymentIds == null) {
            throw new IllegalArgumentException("paymentIds must not be null");
        }
        if (paymentIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("paymentIds must be positive");
        }
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = paymentIds.stream().distinct().toList();
        Map<Long, PaymentSession> sessionByPayment = new HashMap<>();
        for (List<Long> chunk : chunks(distinct)) {
            sessions.findByPaymentIdIn(chunk).forEach(session -> sessionByPayment.put(session.getPaymentId(), session));
        }
        for (Long paymentId : distinct) {
            if (!sessionByPayment.containsKey(paymentId)) {
                unlinkedSession(paymentId).ifPresent(session -> sessionByPayment.put(paymentId, session));
            }
        }
        List<Long> sessionIds = sessionByPayment.values().stream()
                .map(PaymentSession::getSessionId)
                .distinct()
                .toList();
        Map<Long, PaymentOrderContact> contactBySession = new HashMap<>();
        for (List<Long> chunk : chunks(sessionIds)) {
            contacts.findBySessionIdIn(chunk).forEach(contact -> contactBySession.put(contact.getSessionId(), contact));
        }

        // 넘긴 결제 순서대로 처음 보는 지문에 1, 2, 3…을 매긴다 — 나눠 읽어도 번호는 전체 기준
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

    // 세션에 아직 결제 ID가 없는 결제창 결제 — 접수 기록의 가맹점 · 결제 시도 키로 찾는다
    // 다른 결제에 이미 연결된 세션은 쓰지 않는다
    private Optional<PaymentSession> unlinkedSession(Long paymentId) {
        return requests.findByPaymentId(paymentId)
                .flatMap(request ->
                        sessions.findByMerchantIdAndAttemptKey(request.getMerchantId(), request.getIdempotencyKey()))
                .filter(session ->
                        session.getPaymentId() == null || session.getPaymentId().equals(paymentId));
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> chunks = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            chunks.add(ids.subList(from, Math.min(from + CHUNK, ids.size())));
        }
        return chunks;
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
