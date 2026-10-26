package com.donworry.payment.dto;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;

class PaymentOperationsQueryRequestTests {
    // 기본 페이지와 서로 다른 offset의 같은 시각을 정규화한다.
    @Test
    void parse_whenValid_usesDefaultsAndUtc() {
        var input = new LinkedMultiValueMap<String, String>();
        input.add("createdFrom", "2026-10-26T09:00:00+09:00");
        var result = PaymentOperationsQueryRequest.from(input);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.createdFrom()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
    }
    // 잘못된 필터는 기본값이나 다른 검색 의미로 바꾸지 않는다.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "page=-1",
                "size=0",
                "size=101",
                "page=2147483647",
                "size=",
                "paymentId=0",
                "merchantId=-1",
                "paymentId=9223372036854775808",
                "sort=createdAt",
                "category=BAD",
                "paymentStatus=held",
                "orderRef= x",
                "createdFrom=2026-10-26T00:00:00",
                "createdFrom=bad"
            })
    void parse_whenInvalid_rejects(String pair) {
        var parts = pair.split("=", -1);
        var input = new LinkedMultiValueMap<String, String>();
        input.add(parts[0], parts[1]);
        assertThatThrownBy(() -> PaymentOperationsQueryRequest.from(input)).isInstanceOf(RuntimeException.class);
    }
    // 중복 조건과 역전·동일 기간을 거절하고 크기 경계는 허용한다.
    @Test
    void parse_whenBoundaryOrRepeated_checksContract() {
        var input = new LinkedMultiValueMap<String, String>();
        input.add("size", "1");
        assertThat(PaymentOperationsQueryRequest.from(input).size()).isEqualTo(1);
        input.set("size", "100");
        assertThat(PaymentOperationsQueryRequest.from(input).size()).isEqualTo(100);
        input.add("size", "100");
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentOperationsQueryRequest.from(input));
        input.clear();
        input.add("createdFrom", "2026-10-26T00:00:00Z");
        input.add("createdTo", "2026-10-26T00:00:00Z");
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentOperationsQueryRequest.from(input));
        input.set("createdTo", "2026-10-25T00:00:00Z");
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentOperationsQueryRequest.from(input));
        input.clear();
        input.add("orderRef", "x".repeat(101));
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentOperationsQueryRequest.from(input));
    }
}
