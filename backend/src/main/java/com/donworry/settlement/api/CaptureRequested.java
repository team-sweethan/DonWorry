package com.donworry.settlement.api;

import lombok.Builder;

@Builder
public record CaptureRequested(Long paymentId, Long merchantId, String orderId, Long amount) {}
