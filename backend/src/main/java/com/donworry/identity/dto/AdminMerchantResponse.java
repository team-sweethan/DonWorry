package com.donworry.identity.dto;

import com.donworry.identity.entity.Merchant;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record AdminMerchantResponse(
        Long merchantId,
        String merchantCode,
        String displayName,
        String status,
        BigDecimal pgFeeRate,
        LocalDateTime createdAt) {
    public static AdminMerchantResponse from(Merchant merchant) {
        return AdminMerchantResponse.builder()
                .merchantId(merchant.getMerchantId())
                .merchantCode(merchant.getMerchantCode())
                .displayName(merchant.getDisplayName())
                .status(merchant.getStatus().name())
                .pgFeeRate(merchant.getPgFeeRate())
                .createdAt(merchant.getCreatedAt())
                .build();
    }
}
