package com.donworry.payment.service;

import org.springframework.stereotype.Component;

@Component
class MockPaymentRefunder implements PaymentRefunder {
    @Override
    public RefundResult refund(RefundCommand command) {
        if (command == null) throw new IllegalArgumentException("command is required");
        return RefundResult.SUCCEEDED;
    }
}
