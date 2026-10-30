package com.donworry.settlement.dto;

import java.time.LocalDate;

public record CashFlowSettingResponse(long openingBalance, LocalDate baseDate) {}
