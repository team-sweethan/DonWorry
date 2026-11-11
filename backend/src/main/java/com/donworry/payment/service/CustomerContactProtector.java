package com.donworry.payment.service;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 주문자 연락처 보호 (ADR-0082, #602). 원문은 DB에 두지 않는다 — 이름 · 전화는 AES-GCM 암호문, 전화는 HMAC 지문.
// 키는 MerchantKeyCipher와 같은 모양(32바이트 Base64). 운영 값은 환경 변수로만 넣고 저장소에 적지 않는다.
// 키가 없으면 available() = false — 결제는 그대로 진행하고 연락처만 저장하지 않는다.
@Slf4j
@Component
public class CustomerContactProtector {
    public static final String ORDERER_NAME = "ORDERER_NAME";
    public static final String ORDERER_PHONE = "ORDERER_PHONE";
    static final String KEY_ID = "V1";

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    // 하이픈 · 공백을 지운 국내 번호. +82 같은 국가 번호는 받지 않는다
    private static final Pattern PHONE = Pattern.compile("0[0-9]{8,10}");
    private static final int MAX_NAME_LENGTH = 50;

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    public CustomerContactProtector(
            @Value("${donworry.pii.encryption-key:}") String encryptionKey,
            @Value("${donworry.pii.hmac-key:}") String hmacKey) {
        this.encryptionKey = key(encryptionKey, "AES");
        this.hmacKey = key(hmacKey, "HmacSHA256");
        if (!available()) log.warn("주문자 연락처 키가 없어 연락처를 저장하지 않습니다 (donworry.pii.*)");
    }

    public boolean available() {
        return encryptionKey != null && hmacKey != null;
    }

    // 형식이 틀리면 IllegalArgumentException. 세션에 고정되는 값이라 호출 쪽이 400으로 막는다
    public static NormalizedContact normalize(
            String ordererName, String ordererPhone, String pickupName, String pickupPhone) {
        return new NormalizedContact(name(ordererName), phone(ordererPhone), name(pickupName), phone(pickupPhone));
    }

    // 주문자 정보가 없거나 키가 없으면 저장할 것이 없다
    public Optional<ProtectedContact> protect(long merchantId, NormalizedContact contact) {
        if (!available() || !contact.hasOrderer()) return Optional.empty();
        return Optional.of(ProtectedContact.builder()
                .ordererNameEnc(encrypt(merchantId, ORDERER_NAME, contact.ordererName()))
                .ordererPhoneEnc(encrypt(merchantId, ORDERER_PHONE, contact.ordererPhone()))
                .ordererPhoneHmac(phoneFingerprint(contact.ordererPhone()))
                .pickupDiffers(contact.pickupDiffers())
                .requestDigest(requestDigest(contact))
                .keyId(KEY_ID)
                .build());
    }

    // 같은 요청 키로 다시 온 요청의 연락처 비교용. 저장할 연락처가 없으면 null
    public String requestDigestOrNull(NormalizedContact contact) {
        if (!available() || !contact.hasOrderer()) return null;
        return requestDigest(contact);
    }

    // 숫자만 남긴 번호의 지문. 하이픈 유무가 달라도 같은 번호면 같은 값이다
    public String phoneFingerprint(String digits) {
        if (digits == null) return null;
        return hmac("phone\u001f" + digits);
    }

    // 원문 열람(조각 5)에서 쓴다. 다른 가맹점 · 다른 칸으로 옮겨 붙인 암호문은 AAD가 달라 풀리지 않는다
    public String decrypt(long merchantId, String field, String stored) {
        if (stored == null) return null;
        if (!available()) throw new IllegalStateException("주문자 연락처 키가 없습니다");
        try {
            var buffer = ByteBuffer.wrap(Base64.getDecoder().decode(stored));
            var nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            var cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(merchantId, field));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | BufferUnderflowException e) {
            // 예외 메시지에 값이 섞이지 않게 원인은 붙이지 않는다
            throw new IllegalStateException("주문자 연락처 복호화 실패");
        }
    }

    private String encrypt(long merchantId, String field, String plain) {
        if (plain == null) return null;
        try {
            var nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(merchantId, field));
            var cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(nonce.length + cipherText.length)
                            .put(nonce)
                            .put(cipherText)
                            .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("주문자 연락처 암호화 실패");
        }
    }

    // 칸 구분자(\u001f)와 빈 값 표시(\u0000)로 "a|b"와 "ab|"가 같은 지문이 되지 않게 한다. 픽업자도 넣는다(원문은 저장 안 함)
    private String requestDigest(NormalizedContact contact) {
        return hmac(String.join(
                "\u001f",
                "request",
                orEmpty(contact.ordererName()),
                orEmpty(contact.ordererPhone()),
                orEmpty(contact.pickupName()),
                orEmpty(contact.pickupPhone())));
    }

    private String hmac(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("주문자 연락처 지문 실패");
        }
    }

    private static byte[] aad(long merchantId, String field) {
        return ("DONWORRY-CONTACT-" + KEY_ID + "\n" + merchantId + "\n" + field).getBytes(StandardCharsets.UTF_8);
    }

    private static String orEmpty(String value) {
        return value == null ? "\u0000" : value;
    }

    private static String name(String raw) {
        if (raw == null) return null;
        var value = raw.strip();
        if (value.isEmpty()
                || value.codePointCount(0, value.length()) > MAX_NAME_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("name");
        return value;
    }

    private static String phone(String raw) {
        if (raw == null) return null;
        var digits = raw.replace("-", "").replace(" ", "");
        if (!PHONE.matcher(digits).matches()) throw new IllegalArgumentException("phone");
        return digits;
    }

    private static SecretKeySpec key(String encoded, String algorithm) {
        if (encoded == null || encoded.isBlank()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("donworry.pii 키는 Base64여야 합니다");
        }
        if (bytes.length != 32) throw new IllegalStateException("donworry.pii 키는 32바이트여야 합니다");
        return new SecretKeySpec(bytes, algorithm);
    }

    // 정규화된 연락처. 이름은 앞뒤 공백 제거, 전화는 숫자만. 로그에 원문이 남지 않게 toString을 덮는다
    public record NormalizedContact(String ordererName, String ordererPhone, String pickupName, String pickupPhone) {
        public boolean hasOrderer() {
            return ordererName != null || ordererPhone != null;
        }

        // 픽업자 정보가 없으면 null. 보낸 칸만 주문자와 비교한다
        public Boolean pickupDiffers() {
            if (pickupName == null && pickupPhone == null) return null;
            return (pickupName != null && !Objects.equals(pickupName, ordererName))
                    || (pickupPhone != null && !Objects.equals(pickupPhone, ordererPhone));
        }

        @Override
        public String toString() {
            return "NormalizedContact[생략]";
        }
    }

    @Builder
    public record ProtectedContact(
            String ordererNameEnc,
            String ordererPhoneEnc,
            String ordererPhoneHmac,
            Boolean pickupDiffers,
            String requestDigest,
            String keyId) {}
}
