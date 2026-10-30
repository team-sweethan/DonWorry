package com.donworry.identity.service;

import com.donworry.identity.api.TenantReader;
import com.donworry.identity.api.TenantSummary;
import com.donworry.identity.repository.MerchantRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TenantReaderService implements TenantReader {
    private final MerchantRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantSummary> findById(Long merchantId) {
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        return repository
                .findById(merchantId)
                .map(merchant -> TenantSummary.builder()
                        .merchantId(merchant.getMerchantId())
                        .name(merchant.getDisplayName())
                        .status(merchant.getStatus().name())
                        .pgFeeRate(merchant.getPgFeeRate())
                        .build());
    }
}
