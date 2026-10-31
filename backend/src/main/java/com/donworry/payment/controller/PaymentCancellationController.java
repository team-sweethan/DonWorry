package com.donworry.payment.controller;

import com.donworry.payment.dto.*;
import com.donworry.payment.service.*;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.http.enabled", havingValue = "true")
public class PaymentCancellationController {
    private final PaymentMerchantAccess merchants;
    private final MerchantPaymentCancellationService service;

    @ModelAttribute
    void authenticate() {
        merchants.requireMerchant(false);
    }

    @PostMapping("/api/payments/{paymentId}/cancellations")
    ResponseEntity<Object> request(
            @PathVariable Long paymentId,
            @RequestHeader("Idempotency-Key") String requestKey,
            @RequestBody PaymentCancellationSubmitRequest body) {
        var result = service.request(paymentId, key(requestKey), body.reason());
        // Service가 요청 기록을 커밋한 뒤 HTTP 업무 거절로 변환한다.
        int status = switch (result.requestStatus()) {
            case "SUCCEEDED", "ALREADY_CANCELED" -> 200;
            case "PROCESSING", "UNKNOWN" -> result.code() == null ? 202 : 409;
            case "FAILED" -> "CANCELLATION_DECLINED".equals(result.code()) ? 422 : 409;
            default -> 409;
        };
        Object response = result;
        if (status >= 400) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), "취소 요청 결과와 다음 행동을 확인해주세요.");
            problem.setProperty("code", result.code());
            problem.setProperty("requestKey", result.requestKey());
            problem.setProperty("requestStatus", result.requestStatus());
            problem.setProperty("nextAction", result.nextAction());
            response = problem;
        }
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .location(URI.create("/api/payment-cancellation-requests/" + result.requestKey()))
                .body(response);
    }

    @GetMapping("/api/payment-cancellation-requests/{requestKey}")
    ResponseEntity<PaymentCancellationResponse> find(@PathVariable String requestKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.find(key(requestKey)));
    }

    private UUID key(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new PaymentHttpException(400, "INVALID_CANCELLATION_REQUEST", "요청 키 형식을 확인해주세요.");
        }
        return UUID.fromString(value);
    }
}
