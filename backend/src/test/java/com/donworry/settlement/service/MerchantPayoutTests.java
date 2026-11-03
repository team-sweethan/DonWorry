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
        p.complete(9700, at);
        assertThat(p.getPayoutAmount()).isEqualTo(9700);
        assertThat(p.getSettlementTotal()).isEqualTo(9700);
        assertThat(p.getRefundDeductionTotal()).isZero();
        assertThat(p.getOrigin()).isEqualTo(PayoutOrigin.BATCH);
        assertThatThrownBy(() -> p.complete(10000, at.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void complete_whenDateChangesOrAmountNegative_rejects() {
        var at = Instant.parse("2026-11-02T14:59:59Z");
        var p = MerchantPayout.pending(1L, LocalDate.of(2026, 11, 2), at);
        assertThatThrownBy(() -> p.complete(1, at.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.complete(-1, at)).isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isEqualTo(PayoutStatus.PENDING);
    }
}
