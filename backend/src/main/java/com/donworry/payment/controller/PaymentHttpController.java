package com.donworry.payment.controller;

import com.donworry.payment.dto.*;
import com.donworry.payment.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.http.enabled", havingValue = "true")
public class PaymentHttpController {
    private final PaymentMerchantAccess access;
    private final PaymentHttpService service;

    // 본문/조회 인자 해석 전에 인증한다. 요청 파라미터를 인증 정보로 바인딩하지 않는다.
    @ModelAttribute
    void authenticate(HttpServletRequest request) {
        request.setAttribute(
                "payment.authenticatedMerchant", access.requireMerchant("POST".equals(request.getMethod())));
    }

    @PostMapping("/api/payments")
    ResponseEntity<MerchantPaymentResponse> submit(
            @RequestAttribute("payment.authenticatedMerchant") Long merchantId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PaymentSubmitRequest body) {
        var result = service.submit(merchantId, key, body);
        boolean pending = service.processing(result);
        return ResponseEntity.status(pending ? 202 : 200)
                .cacheControl(CacheControl.noStore())
                .location(URI.create("/api/payments/" + result.paymentId()))
                .body(result);
    }

    @GetMapping("/api/payments/{paymentId}")
    ResponseEntity<MerchantPaymentResponse> payment(
            @RequestAttribute("payment.authenticatedMerchant") Long merchantId, @PathVariable Long paymentId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.findPayment(merchantId, paymentId));
    }

    @GetMapping("/api/payment-orders")
    ResponseEntity<MerchantPaymentOrderResponse> order(
            @RequestAttribute("payment.authenticatedMerchant") Long merchantId,
            @RequestParam String orderRef,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long beforeAttemptNo) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.findOrder(merchantId, orderRef, size, beforeAttemptNo));
    }
}
