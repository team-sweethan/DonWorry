package com.donworry.identity.dto;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MerchantApplicationRequestsTests {
    // 프론트와 같은 JSON 사례를 읽어 허용·거절 결과가 함께 회귀 검증되게 한다.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("sharedUrlCases")
    void shopUrl_whenSharedCases_matchesBrowserContract(String url, boolean valid) {
        assertThat(MerchantApplicationRequests.SubmitRequest.builder()
                        .shopUrl(url)
                        .build()
                        .isShopUrlValid())
                .as(url)
                .isEqualTo(valid);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> sharedUrlCases() throws Exception {
        try (var input = MerchantApplicationRequestsTests.class.getResourceAsStream("/identity/shop-url-cases.json")) {
            var cases = tools.jackson.databind.json.JsonMapper.builder().build().readTree(input);
            var values = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
            for (var item : cases)
                values.add(org.junit.jupiter.params.provider.Arguments.of(
                        item.path("url").asString(), item.path("valid").asBoolean()));
            return values.stream();
        }
    }

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
                "ftp://쇼핑몰.한국",
                "https://exa_mple.com",
                "https://_shop.example.com",
                "https://-shop.example.com",
                "https://shop-.example.com",
                "https://shop..example.com",
                "https://.example.com",
                "https://exa%5fmple.com",
                "https://ex%61mple.com"
            })
    void shopUrl_whenInvalidInternationalAuthority_rejects(String url) {
        assertThat(MerchantApplicationRequests.SubmitRequest.builder()
                        .shopUrl(url)
                        .build()
                        .isShopUrlValid())
                .isFalse();
    }
}
