package com.donworry.payment.service;

import org.springframework.stereotype.Component;

@Component
class MockPaymentVoider implements PaymentVoider {
    @Override
    public AuthorizationVoidResult voidAuthorization(AuthorizationVoidCommand command) {
        if (command == null) throw new IllegalArgumentException("command is required");
        return AuthorizationVoidResult.SUCCEEDED;
    }
}
