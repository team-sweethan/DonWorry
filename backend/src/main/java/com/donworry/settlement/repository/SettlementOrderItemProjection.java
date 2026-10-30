package com.donworry.settlement.repository;

public interface SettlementOrderItemProjection {
    String getOrderId();

    String getProductName();

    int getQuantity();

    int getItemSequence();
}
