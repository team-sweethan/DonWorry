package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.entity.OrderFulfillment;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderFulfillmentTests {
    private static final Instant AT = Instant.parse("2026-10-27T01:00:00.123456Z");
    private static final Instant LATER = AT.plusSeconds(10);
    private final PickupScheduleCalculator calculator = new PickupScheduleCalculator();

    // 예정일과 무관하게 실제 완료 시각에서 정확히 72시간을 계산한다
    @Test
    void settlementAt_whenPickupProvided_addsSeventyTwoHours() {
        assertThat(calculator.settlementAt(AT)).isEqualTo(Instant.parse("2026-10-30T01:00:00.123456Z"));
        assertThat(calculator.settlementAt(null)).isNull();
    }

    // 완료 정보가 먼저 와도 예정 정보를 나중에 채우며 동일 재전송은 수정 시각을 바꾸지 않는다
    @Test
    void record_whenCompletionArrivesFirst_preservesBothTimes() {
        var f = OrderFulfillment.awaitingPickup("ORDER", AT);
        f.recordCompletion(AT, AT);
        f.recordSchedule(LATER, LATER);
        f.recordCompletion(AT, LATER.plusSeconds(1));
        f.recordSchedule(LATER, LATER.plusSeconds(1));
        assertThat(f.getMerchantId()).isNull();
        assertThat(f.getFulfilledAt()).isEqualTo(AT);
        assertThat(f.getScheduledFulfillmentAt()).isEqualTo(LATER);
        assertThat(f.getUpdatedAt()).isEqualTo(LATER);
    }

    // 다른 시각은 덮어쓰지 않고 마이크로초보다 작은 입력도 거절한다
    @Test
    void record_whenConflicting_rejectsWithoutOverwriting() {
        var f = OrderFulfillment.awaitingPickup("ORDER", AT);
        f.recordCompletion(AT, AT);
        f.recordSchedule(AT, AT);
        assertThatIllegalStateException().isThrownBy(() -> f.recordCompletion(LATER, LATER));
        assertThatIllegalStateException().isThrownBy(() -> f.recordSchedule(LATER, LATER));
        assertThatIllegalArgumentException().isThrownBy(() -> f.recordSchedule(AT.plusNanos(1), LATER));
        assertThatIllegalArgumentException().isThrownBy(() -> f.recordCompletion(null, LATER));
        assertThat(f.getFulfilledAt()).isEqualTo(AT);
        assertThat(f.getScheduledFulfillmentAt()).isEqualTo(AT);
    }

    // 한 번 연결한 가맹점은 동일 값만 허용한다
    @Test
    void bindMerchant_whenAlreadyLinked_preservesMerchant() {
        var f = OrderFulfillment.awaitingPickup("ORDER", AT);
        f.bindMerchant(1L, AT);
        f.bindMerchant(1L, LATER);
        assertThatIllegalStateException().isThrownBy(() -> f.bindMerchant(2L, LATER));
        assertThatIllegalArgumentException().isThrownBy(() -> f.bindMerchant(0L, LATER));
        assertThat(f.getMerchantId()).isEqualTo(1L);
        assertThat(f.getUpdatedAt()).isEqualTo(AT);
    }
}
