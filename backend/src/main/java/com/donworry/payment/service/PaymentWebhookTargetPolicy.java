package com.donworry.payment.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 웹훅을 보낼 가맹점 주소(notifyUrl) 안전장치 (ADR-0064 남은 결정)
// 가맹점이 내부 주소를 등록하면 돈워리 서버가 자기 내부망으로 요청을 보내게 된다(SSRF). 운영에서는
//   - https만 허용하고
//   - 루프백 · 사설 · 링크 로컬 · 미지정 · 멀티캐스트 · IPv6 고유 로컬 주소로는 보내지 않는다.
// 로컬 개발(CakeShop http://127.0.0.1:8080)은 donworry.payment.webhook.allow-local-targets=true로 둘 다 허용한다
@Component
class PaymentWebhookTargetPolicy {

    private final boolean allowLocalTargets;

    PaymentWebhookTargetPolicy(
            @Value("${donworry.payment.webhook.allow-local-targets:false}") boolean allowLocalTargets) {
        this.allowLocalTargets = allowLocalTargets;
    }

    // 세션 생성 때: 스킴과, 주소가 IP로 적혀 있으면 그 IP만 본다(이름 풀이는 바뀔 수 있어 보내기 직전에 다시 본다)
    boolean acceptableAtCreate(URI uri) {
        if (allowLocalTargets) return true;
        if (!"https".equals(uri.getScheme())) return false;
        InetAddress literal = literalAddress(uri.getHost());
        return literal == null || !internal(literal);
    }

    // 보내기 직전: 호스트 이름을 풀어 나온 모든 주소가 공인 주소여야 한다. 풀 수 없으면 보내지 않는다
    // 이름 풀이와 실제 연결 사이에 주소가 바뀌는 경우(DNS rebinding)까지 막지는 않는다 — 한계로 남긴다
    boolean deliverable(URI uri) {
        if (allowLocalTargets) return true;
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (internal(address)) return false;
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean internal(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] raw = address.getAddress();
        // IPv6 고유 로컬(fc00::/7)과 IPv4 공유 주소(100.64.0.0/10)도 내부로 본다
        if (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) return true;
        return raw.length == 4 && (raw[0] & 0xff) == 100 && (raw[1] & 0xc0) == 64;
    }

    // "1.2.3.4" · "[::1]" 처럼 IP로 적힌 호스트만 주소로 바꾼다. 이름이면 null(여기서 DNS를 부르지 않는다)
    private static InetAddress literalAddress(String host) {
        if (host == null) return null;
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean ipv4 = bare.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = bare.contains(":");
        if (!ipv4 && !ipv6) return null;
        try {
            return InetAddress.getByName(bare);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
