package com.donworry.payment.service;

import com.donworry.identity.api.CurrentMerchant;
import com.donworry.identity.api.MerchantIdentity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentMerchantIdentityAdapter implements PaymentMerchantIdentity {
    private final CurrentMerchant currentMerchant;

    @Override
    public Optional<Long> currentMerchantId() {
        return currentMerchant.findCurrent().map(MerchantIdentity::merchantId);
    }
}
