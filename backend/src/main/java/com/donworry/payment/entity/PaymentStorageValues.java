package com.donworry.payment.entity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

final class PaymentStorageValues {
    private PaymentStorageValues() {}

    static long positive(Long value) {
        if (value == null || value <= 0) throw new IllegalArgumentException("양수가 필요합니다");
        return value;
    }

    static String reference(String value) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > 100)
            throw new IllegalArgumentException("참조는 공백 없는 양끝과 1~100자여야 합니다");
        return value;
    }

    static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "time").truncatedTo(ChronoUnit.MICROS);
    }
}
