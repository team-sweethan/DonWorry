package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.UnaryOperator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentSignalMaskingTests {

    // 짧거나 모양이 다른 값도 원문이 통째로 나가지 않고, 이상한 값은 예외 대신 *** 로 가린다
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
                "email  | chk990727@gmail.com   | ch***@gmail.com",
                "email  | ab@b.com              | a***@b.com",
                "email  | a@b.com               | a***@b.com",
                "email  | no-at-sign            | ***",
            })
    void mask_whenIdentifierShapesDiffer_hidesRest(String kind, String raw, String expected) {
        UnaryOperator<String> mask = switch (kind) {
            case "ip" -> PaymentSignalMasking::ipAddress;
            case "device" -> PaymentSignalMasking::deviceId;
            default -> PaymentSignalMasking::email;
        };
        assertThat(mask.apply(raw)).isEqualTo(expected);
        assertThat(mask.apply(null)).isNull();
    }
}
