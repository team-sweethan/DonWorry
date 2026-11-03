package com.donworry.identity.api;

import jakarta.servlet.http.HttpServletRequest;

// 요청을 보낸 실제 클라이언트 IP. 로그인 이력과 같은 규칙(security.login-history.ip-mode · trusted-proxies)을 쓴다.
// DIRECT면 소켓 상대 주소, PROXY면 신뢰 프록시가 보낸 X-DonWorry-Client-IP만 믿는다.
// 알 수 없거나 믿을 수 없으면 null이다. 다른 모듈은 getRemoteAddr()를 직접 쓰지 않는다 (ADR-0064, PR #425 리뷰)
public interface ClientIpResolver {
    String clientIp(HttpServletRequest request);
}
