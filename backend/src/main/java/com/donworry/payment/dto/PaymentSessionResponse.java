package com.donworry.payment.dto;

import java.time.Instant;

// 결제 준비 응답. checkoutUrl을 구매자 브라우저에서 연다(레이어 iframe·팝업·이동).
public record PaymentSessionResponse(String sessionKey, String checkoutUrl, Instant expiresAt) {}
