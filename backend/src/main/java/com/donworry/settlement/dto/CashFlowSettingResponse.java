package com.donworry.settlement.dto;

import java.time.LocalDate;

public record CashFlowSettingResponse(long openingBalance, LocalDate baseDate, long minimumOperatingFunds) {
    public CashFlowSettingResponse(long openingBalance, LocalDate baseDate) {
        this(openingBalance, baseDate, 0);
    }
}
