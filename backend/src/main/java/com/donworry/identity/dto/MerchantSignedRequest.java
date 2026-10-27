package com.donworry.identity.dto;

import lombok.Builder;

@Builder
public record MerchantSignedRequest(
        String merchantCode,
        String timestamp,
        String nonce,
        String signature,
        String method,
        String target,
        String contentType,
        String idempotencyKey,
        byte[] body) {
    // 요청 본문과 인증 헤더는 예외·디버그 로그의 문자열 표현에 포함하지 않는다.
    @Override
    public String toString() {
        return "MerchantSignedRequest[redacted]";
    }
}
