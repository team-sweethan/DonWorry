package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 웹훅 대상 주소 안전장치 (ADR-0064 남은 결정, #402 PR②). IP로 적은 주소만 써서 DNS 없이 검사한다
class PaymentWebhookTargetPolicyTests {

    private final PaymentWebhookTargetPolicy production = new PaymentWebhookTargetPolicy(false);
    private final PaymentWebhookTargetPolicy local = new PaymentWebhookTargetPolicy(true);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "10.0.0.5",
                "172.16.3.4",
                "192.168.0.10",
                "169.254.169.254",
                "0.0.0.0",
                "100.64.1.1",
                "224.0.0.1",
                "::1",
                "fe80::1",
                "fd12:3456::1"
            })
    void internal_privateLoopbackLinkLocalAndSimilar_areInternal(String address) throws Exception {
        assertThat(PaymentWebhookTargetPolicy.internal(InetAddress.getByName(address)))
                .as(address)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "203.0.113.10", "100.128.0.1", "2001:4860:4860::8888"})
    void internal_publicAddresses_areNot(String address) throws Exception {
        assertThat(PaymentWebhookTargetPolicy.internal(InetAddress.getByName(address)))
                .as(address)
                .isFalse();
    }

    // 운영: https · 공인 주소만. 세션 생성 때는 IP로 적힌 주소만 보고, 이름은 보내기 직전에 푼다
    @Test
    void production_allowsOnlyHttpsPublicTargets() {
        assertThat(production.acceptableAtCreate(URI.create("https://shop.example/hooks")))
                .isTrue();
        assertThat(production.acceptableAtCreate(URI.create("https://8.8.8.8/hooks")))
                .isTrue();
        assertThat(production.acceptableAtCreate(URI.create("http://shop.example/hooks")))
                .isFalse();
        assertThat(production.acceptableAtCreate(URI.create("https://192.168.0.10/hooks")))
                .isFalse();
        assertThat(production.acceptableAtCreate(URI.create("https://[::1]/hooks")))
                .isFalse();

        assertThat(production.deliverable(URI.create("https://8.8.8.8/hooks"))).isTrue();
        assertThat(production.deliverable(URI.create("https://127.0.0.1/hooks")))
                .isFalse();
        assertThat(production.deliverable(URI.create("http://8.8.8.8/hooks"))).isFalse();
        // 이름을 풀 수 없으면 보내지 않는다(.invalid는 예약된 최상위 도메인이라 풀리지 않는다)
        assertThat(production.deliverable(URI.create("https://no-such-host.invalid/hooks")))
                .isFalse();
    }

    // 로컬 개발: CakeShop이 http://127.0.0.1:8080에서 받으므로 설정으로 모두 허용한다
    @Test
    void localTargets_allowHttpAndLoopback() {
        assertThat(local.acceptableAtCreate(URI.create("http://127.0.0.1:8080/hooks")))
                .isTrue();
        assertThat(local.deliverable(URI.create("http://127.0.0.1:8080/hooks"))).isTrue();
    }
}
