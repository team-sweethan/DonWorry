package com.donworry.settlement.repository;

public interface SettlementCompletionCandidate {
    Long getSettlementId();

    Long getMerchantId();

    String getOrderId();
}
