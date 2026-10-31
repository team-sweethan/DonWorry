package com.donworry.payment.service;

import lombok.Builder;

@Builder
record RefundCommand(Long paymentId, Long captureId, Long amount) {
    RefundCommand {
        if (paymentId == null || paymentId <= 0 || captureId == null || captureId <= 0 || amount == null || amount <= 0)
            throw new IllegalArgumentException("환불 대상과 전액이 필요합니다");
    }
}
