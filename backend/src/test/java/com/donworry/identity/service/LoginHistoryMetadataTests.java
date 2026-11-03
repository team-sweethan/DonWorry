package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoginHistoryMetadataTests {
    // 직접 접속에서는 위조한 전달 헤더 대신 숫자 접속 주소만 기록한다
    @Test
    void clientIp_whenDirect_ignoresForwardedHeaders() {
        var metadata = new LoginHistoryRequestMetadata("DIRECT", "", "NONE", "");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8::7");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("X-DonWorry-Client-IP", "203.0.113.10");
        assertThat(metadata.clientIp(request)).isEqualTo("2001:db8:0:0:0:0:0:7");
        request.setRemoteAddr("attacker.example");
        assertThat(metadata.clientIp(request)).isNull();
    }

    // 관리 프록시의 단일 유효 주소만 사용하고 확인 불가를 프록시 주소로 대체하지 않는다
    @Test
    void clientIp_whenProxy_requiresTrustedPeerAndSingleLiteral() {
        var metadata = new LoginHistoryRequestMetadata("PROXY", "127.0.0.1, ::1", "NONE", "");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");
        assertThat(metadata.clientIp(request)).isNull();
        request.addHeader("X-DonWorry-Client-IP", "203.0.113.8");
        assertThat(metadata.clientIp(request)).isEqualTo("203.0.113.8");
        request.setRemoteAddr("10.0.0.2");
        assertThat(metadata.clientIp(request)).isNull();
        request.setRemoteAddr("127.0.0.1");
        for (String invalid : new String[] {
            "127.0.0.1, 203.0.113.8", "256.1.1.1", "01.1.1.1", "host.example", "fe80::1%lo0", "garbage", ""
        }) {
            request.removeHeader("X-DonWorry-Client-IP");
            request.addHeader("X-DonWorry-Client-IP", invalid);
            assertThat(metadata.clientIp(request)).isNull();
        }
        request.removeHeader("X-DonWorry-Client-IP");
        request.addHeader("X-DonWorry-Client-IP", "203.0.113.8");
        request.addHeader("X-DonWorry-Client-IP", "203.0.113.9");
        assertThat(metadata.clientIp(request)).isNull();
    }

    // 원 접속 주소를 바꾸는 서버 설정이나 빈 신뢰 목록은 기동 단계에서 거절한다
    @Test
    void configuration_whenUnsafe_rejectsForwardingAndInvalidModes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LoginHistoryRequestMetadata("PROXY", "", "NONE", ""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LoginHistoryRequestMetadata("UNKNOWN", "", "NONE", ""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LoginHistoryRequestMetadata("DIRECT", "", "FRAMEWORK", ""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LoginHistoryRequestMetadata("DIRECT", "", "NATIVE", ""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LoginHistoryRequestMetadata("DIRECT", "", "NONE", "X-Forwarded-For"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LoginHistoryRequestMetadata("PROXY", "example.com", "NONE", ""));
    }

    // User-Agent의 제어 문자를 제거하고 유니코드 코드 포인트 기준으로 길이를 제한한다
    @Test
    void userAgent_whenUntrusted_boundsAndRemovesControlCharacters() {
        var metadata = new LoginHistoryRequestMetadata("DIRECT", "", "NONE", "");
        var request = new MockHttpServletRequest();
        assertThat(metadata.userAgent(request)).isNull();
        request.addHeader("User-Agent", "\r\n\u202e" + "😀".repeat(600));
        assertThat(metadata.userAgent(request)).isEqualTo("😀".repeat(512));
    }
}
