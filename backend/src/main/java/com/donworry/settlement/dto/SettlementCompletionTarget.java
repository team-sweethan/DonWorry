package com.donworry.settlement.dto;

import lombok.Builder;

@Builder
public record SettlementCompletionTarget(Long settlementId, Long merchantId, String orderId) {}
