package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.UnaryOperator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FdsIdentifierMaskingTests {

    // P5 PaymentSignalMaskingTests와 같은 예시로, 같은 이메일이 거래 목록과 FDS 화면에서 같게 가려지는지 지킨다 (#603)
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "chk990727@gmail.com   | ch***@gmail.com",
                "ab@b.com              | a***@b.com",
                "a@b.com               | a***@b.com",
                "no-at-sign            | ***",
            })
    void email_whenShapesDiffer_matchesPaymentRule(String raw, String expected) {
        assertThat(FdsIdentifierMasking.email(raw)).isEqualTo(expected);
        assertThat(FdsIdentifierMasking.email(null)).isNull();
    }

    // P5 PaymentSignalMaskingTests와 같은 예시로, 같은 IP · 기기 ID가 거래 목록과 FDS 화면에서 같게 가려지는지 지킨다 (#628)
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "ip     | 211.234.10.5          | 211.234.*.*",
                "ip     | 2001:db8:85a3::8a2e   | 2001:db8:*",
                "ip     | 2001::1               | 2001:*",
                "ip     | ::1                   | ***",
                "ip     | not-an-ip             | ***",
                "device | dev-7f3a9c21-abcd     | dev-7f3a…",
                "device | dev12                 | de…",
                "device | x                     | …",
            })
    void ipAddressAndDeviceId_whenShapesDiffer_matchesPaymentRule(String kind, String raw, String expected) {
        UnaryOperator<String> mask =
                kind.equals("ip") ? FdsIdentifierMasking::ipAddress : FdsIdentifierMasking::deviceId;
        assertThat(mask.apply(raw)).isEqualTo(expected);
        assertThat(mask.apply(null)).isNull();
    }
}
