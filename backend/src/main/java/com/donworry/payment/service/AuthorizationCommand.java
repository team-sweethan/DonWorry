package com.donworry.payment.service;

record AuthorizationCommand(Long paymentId, Long amount) {
    AuthorizationCommand {
        if (paymentId == null || paymentId <= 0 || amount == null || amount <= 0) {
            throw new IllegalArgumentException("paymentId and amount must be positive");
        }
    }
}
