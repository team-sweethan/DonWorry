package com.donworry.payment.dto;

import com.donworry.payment.entity.PaymentStatus;
import java.time.Instant;

// 가맹점 notifyUrl로 보내는 결제 상태 알림 본문 (ADR-0064 3절)
// 가맹점은 이 값을 믿지 말고 paymentId로 서명 조회(GET /api/payments/{paymentId})해서 반영한다
// eventId는 같은 상태 변화면 같은 값(evt_{paymentId}_{상태}_{A|N}) — 가맹점은 이것으로 중복을 거른다
public record PaymentWebhookPayload(
        String eventId,
        String type,
        Long paymentId,
        String orderRef,
        PaymentStatus paymentStatus,
        boolean authorized,
        Long amount,
        Instant occurredAt) {

    public static final String TYPE_STATUS_CHANGED = "payment.status_changed";

    public static String eventId(Long paymentId, PaymentStatus status, boolean authorized) {
        return "evt_" + paymentId + "_" + status.name() + "_" + (authorized ? "A" : "N");
    }
}
