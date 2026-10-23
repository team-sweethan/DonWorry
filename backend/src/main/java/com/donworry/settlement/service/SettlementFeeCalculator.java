package com.donworry.settlement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Builder;
import org.springframework.stereotype.Component;

@Component
public class SettlementFeeCalculator {

    // 1차 MVP 임시 수수료율
    private static final BigDecimal CARD_FEE_RATE = new BigDecimal("0.02");

    private static final BigDecimal PG_FEE_RATE = new BigDecimal("0.01");

    public FeeResult calculate(Long grossAmount) {

        if (grossAmount == null || grossAmount <= 0) {
            throw new IllegalArgumentException("정산 대상 금액은 0원보다 커야 합니다.");
        }

        BigDecimal amount = BigDecimal.valueOf(grossAmount);

        // 원 단위 수수료를 항목별로 확정하기 위해 합산 전에 각각 절사한다.
        Long cardFeeAmount =
                amount.multiply(CARD_FEE_RATE).setScale(0, RoundingMode.DOWN).longValueExact();

        Long pgFeeAmount =
                amount.multiply(PG_FEE_RATE).setScale(0, RoundingMode.DOWN).longValueExact();

        Long settlementAmount = grossAmount - cardFeeAmount - pgFeeAmount;

        return FeeResult.builder()
                .cardFeeRate(CARD_FEE_RATE)
                .pgFeeRate(PG_FEE_RATE)
                .cardFeeAmount(cardFeeAmount)
                .pgFeeAmount(pgFeeAmount)
                .settlementAmount(settlementAmount)
                .build();
    }

    @Builder
    public record FeeResult(
            BigDecimal cardFeeRate,
            BigDecimal pgFeeRate,
            Long cardFeeAmount,
            Long pgFeeAmount,
            Long settlementAmount) {}
}
