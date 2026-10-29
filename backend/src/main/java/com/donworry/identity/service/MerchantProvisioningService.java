package com.donworry.identity.service;

import com.donworry.identity.dto.AdminMerchantRequests.CreateMerchantRequest;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.security.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class MerchantProvisioningService {
    private final MerchantRepository merchants;
    private final AdminChangeHistoryService histories;

    @Transactional(propagation = Propagation.MANDATORY)
    public Merchant create(SessionUser actor, CreateMerchantRequest input) {
        if (input.merchantCode() == null
                || !input.merchantCode().matches("[A-Z0-9]{3,40}")
                || input.displayName() == null
                || input.displayName().isBlank()
                || input.displayName().length() > 100
                || input.pgFeeRate() == null
                || input.pgFeeRate().signum() < 0
                || input.pgFeeRate().compareTo(java.math.BigDecimal.ONE) >= 0
                || input.pgFeeRate().scale() > 6) invalid();
        if (merchants.existsByMerchantCode(input.merchantCode()))
            throw new AccountManagementException(AuthErrorCode.ADMIN_MERCHANT_CODE_CONFLICT);
        var merchant = merchants.saveAndFlush(
                Merchant.create(input.merchantCode(), input.displayName().trim(), input.pgFeeRate()));
        histories.recordMerchant(
                actor.userId(),
                merchant.getMerchantId(),
                "MERCHANT_CREATED",
                null,
                Map.of(
                        "merchantCode",
                        merchant.getMerchantCode(),
                        "displayName",
                        merchant.getDisplayName(),
                        "status",
                        merchant.getStatus().name(),
                        "pgFeeRate",
                        merchant.getPgFeeRate()));
        return merchant;
    }

    private void invalid() {
        throw new AccountManagementException(AuthErrorCode.ADMIN_INVALID_INPUT);
    }
}
