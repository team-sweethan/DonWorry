package com.donworry.payment.controller;

import com.donworry.identity.api.ClientIpResolver;
import com.donworry.payment.dto.CheckoutResponses;
import com.donworry.payment.service.PaymentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 공개 결제창 API. 로그인 없이 세션 키(추측 불가 128비트)로만 접근한다.
// 구매자 브라우저에서 부르므로 가맹점 서명은 쓰지 않는다. 결과는 가맹점이 서명 조회로 다시 확인한다.
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.http.enabled", havingValue = "true")
public class PaymentCheckoutController {
    private final PaymentSessionService sessions;
    // 프록시(운영 nginx · 개발 Vite) 뒤에서도 구매자 IP를 쓰도록 identity의 신뢰 프록시 규칙으로 읽는다 (PR #425 리뷰)
    private final ClientIpResolver clientIps;

    @GetMapping("/api/checkout/{sessionKey}")
    ResponseEntity<CheckoutResponses.Session> open(@PathVariable String sessionKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.open(sessionKey));
    }

    @PostMapping("/api/checkout/{sessionKey}/payments")
    ResponseEntity<CheckoutResponses.Result> pay(
            @PathVariable String sessionKey,
            @Valid @RequestBody CheckoutResponses.PayRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessions.pay(sessionKey, body, clientIps.clientIp(request)));
    }

    @GetMapping("/api/checkout/{sessionKey}/result")
    ResponseEntity<CheckoutResponses.Result> result(@PathVariable String sessionKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.result(sessionKey));
    }
}
