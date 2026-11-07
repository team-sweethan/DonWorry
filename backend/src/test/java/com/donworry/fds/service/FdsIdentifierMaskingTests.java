package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

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
}
