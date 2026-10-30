package com.donworry.identity.api;

import java.math.BigDecimal;
import lombok.Builder;

// pgFeeRate는 저장된 비율이다 (0.020000 = 2%). 조회 시 반올림하지 않는다.
@Builder
public record TenantSummary(Long merchantId, String name, String status, BigDecimal pgFeeRate) {}
