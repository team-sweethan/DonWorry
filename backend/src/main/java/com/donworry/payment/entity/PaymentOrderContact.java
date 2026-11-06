package com.donworry.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

// 결제 준비 요청의 주문자 연락처 (ADR-0082, #602). 원문 칸이 없다 — 암호문 · 전화 지문 · 픽업자 다름 여부만
@Entity
@Table(name = "payment_order_contacts")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOrderContact {
    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "orderer_name_enc", length = 512)
    private String ordererNameEnc;

    @Column(name = "orderer_phone_enc", length = 512)
    private String ordererPhoneEnc;

    @Column(name = "orderer_phone_hmac", length = 64)
    private String ordererPhoneHmac;

    @Column(name = "pickup_differs")
    private Boolean pickupDiffers;

    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;

    @Column(name = "key_id", nullable = false, length = 10)
    private String keyId;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    public static PaymentOrderContact of(
            Long sessionId,
            Long merchantId,
            String ordererNameEnc,
            String ordererPhoneEnc,
            String ordererPhoneHmac,
            Boolean pickupDiffers,
            String requestDigest,
            String keyId,
            Instant createdAt) {
        return PaymentOrderContact.builder()
                .sessionId(Objects.requireNonNull(sessionId, "sessionId"))
                .merchantId(PaymentStorageValues.positive(merchantId))
                .ordererNameEnc(ordererNameEnc)
                .ordererPhoneEnc(ordererPhoneEnc)
                .ordererPhoneHmac(ordererPhoneHmac)
                .pickupDiffers(pickupDiffers)
                .requestDigest(Objects.requireNonNull(requestDigest, "requestDigest"))
                .keyId(Objects.requireNonNull(keyId, "keyId"))
                .createdAt(PaymentStorageValues.micros(createdAt))
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentOrderContact(
            Long sessionId,
            Long merchantId,
            String ordererNameEnc,
            String ordererPhoneEnc,
            String ordererPhoneHmac,
            Boolean pickupDiffers,
            String requestDigest,
            String keyId,
            Instant createdAt) {
        this.sessionId = sessionId;
        this.merchantId = merchantId;
        this.ordererNameEnc = ordererNameEnc;
        this.ordererPhoneEnc = ordererPhoneEnc;
        this.ordererPhoneHmac = ordererPhoneHmac;
        this.pickupDiffers = pickupDiffers;
        this.requestDigest = requestDigest;
        this.keyId = keyId;
        this.createdAt = createdAt;
    }
}
