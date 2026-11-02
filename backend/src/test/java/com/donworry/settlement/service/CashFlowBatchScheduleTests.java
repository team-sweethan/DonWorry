package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CashFlowBatchScheduleTests {
    // 03시 정각은 그 배치에 포함하고 1마이크로초 뒤면 다음 날 배치에 반영한다.
    @Test
    void projectedAt_whenAroundKoreanThreeAM_usesInclusiveNextBatch() {
        var policy = new CashFlowBatchSchedule("0 0 3 * * *", false);
        var batch = Instant.parse("2026-11-01T18:00:00Z");
        assertThat(policy.projectedAt(batch.minusNanos(1000))).isEqualTo(batch);
        assertThat(policy.projectedAt(batch)).isEqualTo(batch);
        assertThat(policy.projectedAt(batch.plusNanos(1000))).isEqualTo(batch.plusSeconds(86400));
        assertThat(policy.enabled()).isFalse();
    }

    // 운영에서 배치 cron을 바꾸면 예측도 같은 설정을 읽는다.
    @Test
    void projectedAt_whenCronChanged_usesConfiguredSchedule() {
        var policy = new CashFlowBatchSchedule("0 0 5 * * *", true);
        assertThat(policy.projectedAt(Instant.parse("2026-11-01T18:00:01Z")))
                .isEqualTo(Instant.parse("2026-11-01T20:00:00Z"));
        assertThat(policy.enabled()).isTrue();
    }
    // Spring의 cron 중지 값은 애플리케이션 기동을 막지 않고 예상 일정 조회만 명시적으로 거절한다.
    @Test
    void projectedAt_whenCronDisabled_returnsUnavailableSchedule() {
        var policy = new CashFlowBatchSchedule("-", true);
        assertThat(policy.enabled()).isFalse();
        assertThatThrownBy(() -> policy.projectedAt(Instant.parse("2026-11-01T18:00:00Z")))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }
}
