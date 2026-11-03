package com.donworry.settlement.service;

import java.time.Instant;
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

    public boolean enabled() {
        return enabled;
    }
}
