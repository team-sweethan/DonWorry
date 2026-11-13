package com.donworry.settlement.service;

import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
public class CashFlowBatchSchedule {
    private final String expression;
    private final boolean enabled;
    private final CronExpression cron;

    public CashFlowBatchSchedule(
            @Value("${settlement.completion.cron:0 0 3 * * *}") String expression,
            @Value("${settlement.completion.enabled:false}") boolean enabled) {
        this.expression = expression;
        this.cron = "-".equals(expression) ? null : CronExpression.parse(expression);
        this.enabled = enabled && cron != null;
    }

    public Instant projectedAt(Instant eligibleAt) {
        if (cron == null)
            throw new SettlementRequestException(409, "SETTLEMENT_BATCH_SCHEDULE_UNAVAILABLE", "정산 배치가 중지되어 있습니다.");
        var next = cron.next(eligibleAt.atZone(SettlementReportService.ZONE).minusNanos(1));
        if (next == null)
            throw new SettlementRequestException(409, "SETTLEMENT_BATCH_SCHEDULE_UNAVAILABLE", "정산 배치 일정을 확인해 주세요.");
        return next.toInstant();
    }

    public String expression() {
        return expression;
    }

    Optional<Instant> startupCutoff(Instant now) {
        if (!enabled) return Optional.empty();
        var localNow = now.atZone(SettlementReportService.ZONE);
        var firstToday = cron.next(
                localNow.toLocalDate().atStartOfDay(localNow.getZone()).minusNanos(1));
        // 오늘 지급 원장이 하루 한 건이므로 첫 정기 실행 전에 별도 지급을 만들지 않는다.
        if (firstToday != null
                && firstToday.toLocalDate().equals(localNow.toLocalDate())
                && firstToday.isAfter(localNow)) return Optional.empty();

        // 이 시각 이후에 실행 시각이 남아 있는지를 검사해 마지막 실행 시각을 찾는다.
        long low = 0;
        long high = now.getEpochSecond();
        while (low <= high) {
            long middle = low + (high - low) / 2;
            var next = cron.next(
                    Instant.ofEpochSecond(middle).atZone(localNow.getZone()).minusNanos(1));
            if (next != null && !next.isAfter(localNow)) low = middle + 1;
            else high = middle - 1;
        }
        return high < 0 ? Optional.empty() : Optional.of(Instant.ofEpochSecond(high));
    }

    public boolean enabled() {
        return enabled;
    }
}
