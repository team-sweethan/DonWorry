package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.settlement.entity.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class MerchantPayoutTests {
    @Test
    void complete_whenKoreanDateMatches_storesImmutableZeroDeductionSnapshot() {
        var at = Instant.parse("2026-11-02T18:00:00Z");
        var p = MerchantPayout.pending(1L, LocalDate.of(2026, 11, 3), at);
        p.complete(9700, 0, at);
        assertThat(p.getPayoutAmount()).isEqualTo(9700);
        assertThat(p.getSettlementTotal()).isEqualTo(9700);
        assertThat(p.getRefundDeductionTotal()).isZero();
        assertThat(p.getOrigin()).isEqualTo(PayoutOrigin.BATCH);
        assertThatThrownBy(() -> p.complete(10000, 0, at.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void complete_whenDateChangesOrAmountNegative_rejects() {
        var at = Instant.parse("2026-11-02T14:59:59Z");
        var p = MerchantPayout.pending(1L, LocalDate.of(2026, 11, 2), at);
        assertThatThrownBy(() -> p.complete(1, 0, at.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.complete(-1, 0, at)).isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isEqualTo(PayoutStatus.PENDING);
    }

    // 차감은 순정산 합계를 넘지 않으며 지급액이 0인 경우도 정상 확정한다.
    @Test
    void complete_whenRecoveryConsumesAllIncome_preservesTotalsAndZeroCash() {
        var at = Instant.parse("2026-11-02T18:00:00Z");
        var p = MerchantPayout.pending(1L, LocalDate.of(2026, 11, 3), at);
        p.complete(9700, 9700, at);
        assertThat(p.getSettlementTotal()).isEqualTo(9700);
        assertThat(p.getRefundDeductionTotal()).isEqualTo(9700);
        assertThat(p.getPayoutAmount()).isZero();
        assertThat(p.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
    }
    // 잘못된 차감으로 음수 지급이나 합계보다 큰 지급을 만들지 않는다.
    @Test
    void complete_whenDeductionOutOfRange_rejectsWithoutChangingPayout() {
        var at = Instant.parse("2026-11-02T18:00:00Z");
        var p = MerchantPayout.pending(1L, LocalDate.of(2026, 11, 3), at);
        assertThatThrownBy(() -> p.complete(100, -1, at)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.complete(100, 101, at)).isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isEqualTo(PayoutStatus.PENDING);
        assertThat(p.getRefundDeductionTotal()).isZero();
    }
}
