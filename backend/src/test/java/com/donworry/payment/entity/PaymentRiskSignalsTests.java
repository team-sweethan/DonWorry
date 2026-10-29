package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentRiskSignalsTests {
    // 국가 코드 · 이메일은 P2와 같은 모양으로 정규화하고 빈 값은 미수집으로 둔다
    @Test
    void signals_normalizeLikeFdsAndDefaultToNone() {
        var signals = new PaymentRiskSignals(
                "device-1", "token-1", "신한 **** **** **** 1234", "kr", "203.0.113.10", "Buyer@Example.COM");
        assertThat(signals.ipCountryCode()).isEqualTo("KR");
        assertThat(signals.buyerEmail()).isEqualTo("buyer@example.com");
        assertThat(PaymentRiskSignals.orNone(null)).isEqualTo(PaymentRiskSignals.none());
        assertThat(PaymentRiskSignals.none().deviceId()).isNull();
    }

    // IPv4 · IPv6 리터럴만 받고 이름 조회가 필요한 값은 거절한다
    @Test
    void ipAddress_acceptsOnlyLiterals() {
        assertThat(PaymentRiskSignals.builder().ipAddress("::1").build().ipAddress())
                .isEqualTo("::1");
        assertThat(PaymentRiskSignals.builder()
                        .ipAddress("2001:db8::ff00:42:8329")
                        .build()
                        .ipAddress())
                .isEqualTo("2001:db8::ff00:42:8329");
        for (var invalid : new String[] {"256.0.0.1", "localhost", "1.2.3", "2001:db8::zz", ":::"})
            assertThatIllegalArgumentException()
                    .as(invalid)
                    .isThrownBy(() ->
                            PaymentRiskSignals.builder().ipAddress(invalid).build());
    }

    // 카드 원문을 받지 않는다: 마스킹하지 않은 번호가 든 표시값과 번호 자체인 토큰은 거절한다
    @ParameterizedTest
    @ValueSource(strings = {"신한 1234567890123456", "4111-1111-1111-1111", "카드 4111 1111 1111 1111"})
    void cardDisplayLabel_rejectsUnmaskedCardNumber(String label) {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        PaymentRiskSignals.builder().cardDisplayLabel(label).build());
    }

    @Test
    void cardToken_rejectsRawCardNumberButAllowsHash() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentRiskSignals.builder()
                        .cardToken("4111111111111111")
                        .build());
        var hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        assertThat(PaymentRiskSignals.builder().cardToken(hash).build().cardToken())
                .isEqualTo(hash);
    }

    // 길이 · 공백 · 형식이 P2 계약과 다르면 저장 전에 거절한다
    @Test
    void signals_rejectInvalidShapes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentRiskSignals.builder().deviceId(" d").build());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentRiskSignals.builder().deviceId("").build());
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        PaymentRiskSignals.builder().deviceId("d".repeat(101)).build());
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> PaymentRiskSignals.builder().ipCountryCode("K1").build());
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> PaymentRiskSignals.builder().ipCountryCode("KOR").build());
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        PaymentRiskSignals.builder().buyerEmail("not-email").build());
    }
}
