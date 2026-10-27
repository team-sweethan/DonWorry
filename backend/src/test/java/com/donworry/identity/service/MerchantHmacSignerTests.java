package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.identity.dto.MerchantSignedRequest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class MerchantHmacSignerTests {
    private final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private MerchantSignedRequest request(String target, String keyHeader, String body) {
        return new MerchantSignedRequest(
                "CAKESHOP",
                "1793077200",
                "123e4567-e89b-42d3-a456-426614174000",
                "",
                "POST",
                target,
                "application/json",
                keyHeader,
                body.getBytes(StandardCharsets.UTF_8));
    }

    // 독립 구현으로 만든 고정 벡터와 바이트 단위로 같은 서명을 만든다
    @Test
    void sign_whenKnownRequest_matchesIndependentVector() {
        assertThat(HexFormat.of()
                        .formatHex(MerchantHmacSigner.sign(
                                key, request("/api/payments", "attempt-1", "{\"amount\":1000}"))))
                .isEqualTo("66f90e57326719bb6b13236db46795949aafb19ba49fb7888f472bbcdb8e448e");
    }

    // 본문·조회 조건·멱등 키를 하나만 바꿔도 원래 서명을 사용할 수 없다
    @Test
    void sign_whenSignedComponentsChange_producesDifferentSignatures() {
        byte[] original = MerchantHmacSigner.sign(key, request("/api/payments?orderRef=A", "attempt-1", "{}"));
        assertThat(MerchantHmacSigner.sign(key, request("/api/payments?orderRef=B", "attempt-1", "{}")))
                .isNotEqualTo(original);
        assertThat(MerchantHmacSigner.sign(key, request("/api/payments?orderRef=A", "attempt-2", "{}")))
                .isNotEqualTo(original);
        assertThat(MerchantHmacSigner.sign(key, request("/api/payments?orderRef=A", "attempt-1", "{ }")))
                .isNotEqualTo(original);
    }

    // 서명 입력의 문자열 표현에 요청 본문이나 비밀 헤더가 들어가지 않는다
    @Test
    void request_whenStringified_redactsInput() {
        assertThat(request("/api/payments", "secret-key-header", "private-body").toString())
                .doesNotContain("secret-key-header", "private-body");
    }
}
