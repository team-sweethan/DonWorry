package com.donworry.identity.service;

import com.donworry.identity.api.MerchantWebhookSigner;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.security.MerchantAuthenticationException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 결제 상태 웹훅 서명 (ADR-0064 3절). 가맹점 키는 API 서명 검증과 같은 MerchantSecretService에서 꺼낸다
// 2026-11-02: P1 대신 P5(수민)가 구현, identity 코드라 P1 리뷰
@Service
@RequiredArgsConstructor
public class MerchantWebhookSignerService implements MerchantWebhookSigner {

    static final String PREFIX = "DONWORRY-WEBHOOK-V1";

    // 헤더 · 서명 원문에 들어가므로 줄바꿈 · 공백 없이 짧게. 돈워리 형식은 evt_{paymentId}_{상태}_{A|N}
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private final MerchantRepository merchants;
    private final MerchantSecretService secrets;
    private final Clock identityClock;

    @Override
    public Optional<SignedWebhook> sign(Long merchantId, String eventId, byte[] body) {
        if (merchantId == null || eventId == null || !EVENT_ID.matcher(eventId).matches() || body == null) {
            throw new IllegalArgumentException("웹훅 서명 입력이 올바르지 않습니다");
        }
        String merchantCode = merchants
                .findById(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("없는 가맹점입니다 merchantId=" + merchantId))
                .getMerchantCode();
        byte[] key;
        try {
            key = secrets.resolve(merchantCode).key();
        } catch (MerchantAuthenticationException noUsableKey) {
            // 연동 전 · 키 미발급 · 키 사용 불가 — 웹훅은 나중에 다시 보낸다. 키 오류 내용은 밖으로 내보내지 않는다
            return Optional.empty();
        }
        String timestamp = String.valueOf(identityClock.instant().getEpochSecond());
        return Optional.of(new SignedWebhook(
                merchantCode, timestamp, eventId, signature(key, merchantCode, timestamp, eventId, body)));
    }

    // 서명 원문: PREFIX \n 가맹점 코드 \n timestamp \n eventId \n sha256(body) hex. 결과는 소문자 hex 64자
    static String signature(byte[] key, String merchantCode, String timestamp, String eventId, byte[] body) {
        Objects.requireNonNull(key, "key");
        try {
            String bodyHash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", PREFIX, merchantCode, timestamp, eventId, bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }
}
