package com.donworry.payment.controller;

import com.donworry.payment.dto.PaymentFdsRecoveryResponse;
import com.donworry.payment.service.PaymentFdsReevaluationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PaymentFdsReevaluationController {
    private final PaymentFdsReevaluationService service;

    @PostMapping("/api/payment-operations/{paymentId}/fds-reevaluations")
    ResponseEntity<PaymentFdsRecoveryResponse> reevaluate(@PathVariable long paymentId, HttpServletRequest request)
            throws IOException {
        // 쿼리 및 chunked 본문도 거부한다. P2의 IllegalArgumentException과 입력 오류를 구분한다.
        if (paymentId <= 0
                || request.getQueryString() != null
                || request.getInputStream().read() != -1) throw new InvalidRequestException();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reevaluate(paymentId));
    }

    static class InvalidRequestException extends RuntimeException {}
}
