package com.donworry.payment.dto;

import java.time.Instant;
import lombok.Builder;

// 거래 내역 한 줄 (#363). 카드 토큰 · IP · 기기 ID · 이메일 원문과 승인 참조는 담지 않는다
// masked* 칸은 서버에서 가린 값이다 — 원문으로 착각하지 않게 이름에 드러낸다
@Builder
public record PaymentTransactionResponse(
        Long paymentId,
        Instant createdAt,
        Long merchantId,
        String customerRef,
        String orderRef,
        Long attemptNo,
        Long amount,
        String paymentStatus,
        String fdsResult,
        Long evaluationId,
        String cardDisplayLabel,
        String ipCountryCode,
        String maskedIpAddress,
        String maskedDeviceId,
        String maskedBuyerEmail) {}
