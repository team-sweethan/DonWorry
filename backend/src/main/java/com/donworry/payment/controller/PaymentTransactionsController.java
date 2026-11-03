package com.donworry.payment.controller;

import com.donworry.payment.dto.PaymentTransactionsPageResponse;
import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.service.PaymentTransactionsQueryService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 보안 직원 거래 내역 (#363). 권한(SECURITY_STAFF · ADMIN)은 identity SecurityConfig가 검사한다
@RestController
@RequiredArgsConstructor
public class PaymentTransactionsController {
    private final PaymentTransactionsQueryService service;

    @GetMapping("/api/payment-transactions")
    ResponseEntity<PaymentTransactionsPageResponse> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.findPage(PaymentTransactionsQueryRequest.of(parameters, Instant.now())));
    }
}
