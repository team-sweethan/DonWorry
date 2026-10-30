package com.donworry.identity.dto;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MerchantApplicationRequestsTests {
    // 국제화 도메인과 ASCII 표현을 모두 허용하되 입력한 경로·쿼리는 변환하지 않는다.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://쇼핑몰.한국/Products?item=ABC",
                "https://xn--2j1bq83a3qd.xn--3e0b707e/Products?item=ABC",
                "https://쇼핑몰.한국:8443/상품?item=ABC",
                "https://[::1]:8080/shop",
                "HTTP://localhost:8080/shop"
            })
    void shopUrl_whenInternationalOrAsciiHost_acceptsAndPreservesOriginal(String url) {
        var input = MerchantApplicationRequests.SubmitRequest.builder()
                .shopUrl(" " + url + " ")
                .build();
        assertThat(input.isShopUrlValid()).isTrue();
        assertThat(input.shopUrl()).isEqualTo(url);
    }

    // IDN 변환으로 잘못된 authority나 user-info를 정상 주소로 바꾸지 않는다.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://user:pass@쇼핑몰.한국",
                "https://@쇼핑몰.한국",
                "https://쇼핑몰.한국:abc",
                "https://쇼핑몰..한국",
                "https://-쇼핑몰.한국",
                "https:///쇼핑몰.한국",
                "https://쇼핑몰.한국/공 백",
                "ftp://쇼핑몰.한국"
            })
    void shopUrl_whenInvalidInternationalAuthority_rejects(String url) {
        assertThat(MerchantApplicationRequests.SubmitRequest.builder()
                        .shopUrl(url)
                        .build()
                        .isShopUrlValid())
                .isFalse();
    }
}
