package com.donworry.payment.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;

class PaymentTransactionsQueryRequestTests {
    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");

    // 31일 초과 · 거꾸로 된 기간 · 모르는 조건 · 중복 조건 · 범위 밖 페이지는 거르지 않은 목록 대신 400으로 막는다
    @ParameterizedTest
    @ValueSource(
            strings = {
                "from=2026-09-28T03:00:00Z",
                "from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:01Z",
                "from=2026-10-30T03:00:00Z&to=2026-10-30T03:00:00Z",
                "from=2026-10-30T04:00:00Z&to=2026-10-30T03:00:00Z",
                "from=2026-10-30",
                "customerref=customer-abc",
                "customerRef=a&customerRef=b",
                "customerRef=%20customer-abc",
                "merchantId=0",
                "status=CAPTURE",
                "size=101",
                "page=-1",
            })
    void queryRequest_whenPeriodOrParameterInvalid_rejects(String query) {
        var parameters = new LinkedMultiValueMap<String, String>();
        for (String pair : query.split("&")) {
            var keyValue = pair.split("=", 2);
            parameters.add(keyValue[0], URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> PaymentTransactionsQueryRequest.of(parameters, NOW))
                .isInstanceOfAny(IllegalArgumentException.class, java.time.DateTimeException.class);
    }
}
