package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CustomerContactProtectorTests {
    // 테스트 전용 32바이트 키 ("A" · "B" 32개). 운영 키가 아니다
    static final String ENCRYPTION_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    static final String HMAC_KEY = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=";
    private static final long MERCHANT_ID = 7L;

    private final CustomerContactProtector protector = new CustomerContactProtector(ENCRYPTION_KEY, HMAC_KEY);

    // 지금 저장한 암호문을 조각 5(원문 열람)에서 풀 수 있어야 한다 — 못 풀면 주문자 정보가 사라진 것과 같다
    @Test
    void protect_thenDecrypt_returnsOriginal() {
        var stored = protector
                .protect(MERCHANT_ID, CustomerContactProtector.normalize(" 김민수 ", "010-1234-5678", null, null))
                .orElseThrow();

        assertThat(protector.decrypt(MERCHANT_ID, CustomerContactProtector.ORDERER_NAME, stored.ordererNameEnc()))
                .isEqualTo("김민수");
        assertThat(protector.decrypt(MERCHANT_ID, CustomerContactProtector.ORDERER_PHONE, stored.ordererPhoneEnc()))
                .isEqualTo("01012345678");
    }

    // 다른 가맹점 · 다른 칸으로 옮겨 붙인 암호문은 풀리지 않는다 (AAD)
    @Test
    void decrypt_withOtherMerchantOrField_fails() {
        var stored = protector
                .protect(MERCHANT_ID, CustomerContactProtector.normalize("김민수", "010-1234-5678", null, null))
                .orElseThrow();

        assertThatThrownBy(
                        () -> protector.decrypt(8L, CustomerContactProtector.ORDERER_PHONE, stored.ordererPhoneEnc()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                        protector.decrypt(MERCHANT_ID, CustomerContactProtector.ORDERER_NAME, stored.ordererPhoneEnc()))
                .isInstanceOf(IllegalStateException.class);
    }

    // 같은 번호는 하이픈 · 공백이 달라도 같은 지문이다 — 조각 5의 번호 대조 근거
    @Test
    void phoneFingerprint_withOrWithoutHyphen_isEqual() {
        var hyphen = CustomerContactProtector.normalize(null, "010-1234-5678", null, null);
        var digits = CustomerContactProtector.normalize(null, "01012345678", null, null);
        var spaced = CustomerContactProtector.normalize(null, "010 1234 5678", null, null);

        var fingerprint = protector.phoneFingerprint(hyphen.ordererPhone());
        assertThat(protector.phoneFingerprint(digits.ordererPhone())).isEqualTo(fingerprint);
        assertThat(protector.phoneFingerprint(spaced.ordererPhone())).isEqualTo(fingerprint);
        assertThat(protector.phoneFingerprint("01012345679")).isNotEqualTo(fingerprint);
    }
}
