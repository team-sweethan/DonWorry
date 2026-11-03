package com.donworry.payment.controller;

import com.donworry.payment.dto.*;
import com.donworry.payment.service.PaymentOperationsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PaymentOperationsController {
    private final PaymentOperationsQueryService service;

    @GetMapping("/api/payment-operations")
    ResponseEntity<PaymentOperationsPageResponse> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.findPage(PaymentOperationsQueryRequest.from(parameters)));
    }

    @GetMapping("/api/payment-operations/{paymentId}")
    ResponseEntity<PaymentOperationDetailResponse> detail(
            @PathVariable long paymentId, @RequestParam MultiValueMap<String, String> parameters) {
        if (!parameters.isEmpty()) throw new IllegalArgumentException();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.findPayment(paymentId));
    }
}
