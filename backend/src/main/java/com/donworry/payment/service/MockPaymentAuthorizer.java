package com.donworry.payment.service;

import org.springframework.stereotype.Component;

@Component
class MockPaymentAuthorizer implements PaymentAuthorizer {
    @Override
    public AuthorizationResult authorize(AuthorizationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return new AuthorizationResult(AuthorizationResult.Type.SUCCEEDED, "MOCK-AUTH-" + command.paymentId(), null);
    }
}
