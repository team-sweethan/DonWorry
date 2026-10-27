package com.donworry.identity.service;

import com.donworry.identity.api.CurrentMerchant;
import com.donworry.identity.api.MerchantIdentity;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentMerchantService implements CurrentMerchant {
    @Override
    public Optional<MerchantIdentity> findCurrent() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof MerchantIdentity merchant)) return Optional.empty();
        return Optional.of(merchant);
    }
}
