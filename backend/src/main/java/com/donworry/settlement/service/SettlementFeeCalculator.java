package com.donworry.settlement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Builder;
import org.springframework.stereotype.Component;

@Component
public class SettlementFeeCalculator {

    // 외부 카드사 연동이 없는 모의 정산의 고정 카드 수수료율이다.
    private static final BigDecimal CARD_FEE_RATE = new BigDecimal("0.02");

    public FeeResult calculate(Long grossAmount, BigDecimal pgFeeRate) {

        if (grossAmount == null || grossAmount <= 0) {
            throw new IllegalArgumentException("정산 대상 금액은 0원보다 커야 합니다.");
        }

        if (pgFeeRate == null
                || pgFeeRate.signum() < 0
                || pgFeeRate.compareTo(BigDecimal.ONE) >= 0
                || pgFeeRate.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("PG 수수료율은 소수 6자리 이내의 0 이상 1 미만 비율이어야 합니다.");
        }

        BigDecimal amount = BigDecimal.valueOf(grossAmount);

        // 원 단위 수수료를 항목별로 확정하기 위해 합산 전에 각각 절사한다.
        Long cardFeeAmount =
                amount.multiply(CARD_FEE_RATE).setScale(0, RoundingMode.DOWN).longValueExact();

        Long pgFeeAmount =
                amount.multiply(pgFeeRate).setScale(0, RoundingMode.DOWN).longValueExact();

        Long settlementAmount = grossAmount - cardFeeAmount - pgFeeAmount;
        if (settlementAmount < 0) {
            throw new IllegalArgumentException("수수료 합계가 정산 대상 금액을 초과할 수 없습니다.");
        }

        return FeeResult.builder()
                .cardFeeRate(CARD_FEE_RATE)
                .pgFeeRate(pgFeeRate)
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
