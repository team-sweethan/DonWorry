package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.identity.dto.MerchantSignedRequest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    // 문서의 Python 예시로 생성한 두 픽업 서명은 Java 구현과 바이트 단위로 일치한다
    @ParameterizedTest
    @CsvSource({
        "schedule,5b785c814ec2b9f678ab7d2b8e012821159aea609a70d7010eb5fe6f5ac3a429",
        "completion,96e4bb0f38073f9c87591f05d7f2f4bfcdf088e328f71a18573459573b8e52a8"
    })
    void sign_whenPickupRequest_matchesPythonExample(String action, String signature) {
        String field = "schedule".equals(action) ? "scheduledPickupAt" : "pickedUpAt";
        String body = "{\"" + field + "\":\"2026-10-27T06:00:00Z\"}";
        var request = new MerchantSignedRequest(
                "CAKESHOP",
                "1793080800",
                "11111111-1111-4111-8111-111111111111",
                "",
                "PUT",
                "/api/v1/order-fulfillments/ORDER-1/" + action,
                "application/json",
                "",
                body.getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(MerchantHmacSigner.sign(key, request)))
                .isEqualTo(signature);
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
