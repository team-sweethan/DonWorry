package com.donworry.settlement.controller;

import com.donworry.settlement.dto.*;
import com.donworry.settlement.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "settlement.pickup-http.enabled", havingValue = "true")
@RequestMapping("/api/v1/order-fulfillments")
public class PickupController {
    private final PickupAccessService access;
    private final OrderFulfillmentService service;

    @ModelAttribute
    void authenticate(HttpServletRequest request) {
        request.setAttribute("settlement.pickupMerchant", access.requireMerchant());
    }

    @PutMapping("/{orderId}/schedule")
    public ResponseEntity<PickupResponse> schedule(
            @RequestAttribute("settlement.pickupMerchant") Long merchantId,
            @PathVariable String orderId,
            @Valid @RequestBody PickupScheduleRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.recordSchedule(merchantId, orderId, request.scheduledPickupAt(), request.items()));
    }

    @PutMapping("/{orderId}/completion")
    public ResponseEntity<PickupResponse> completion(
            @RequestAttribute("settlement.pickupMerchant") Long merchantId,
            @PathVariable String orderId,
            @Valid @RequestBody PickupCompletionRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.recordCompletion(merchantId, orderId, request.pickedUpAt()));
    }
}
