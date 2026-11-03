package com.donworry.settlement.service;

import com.donworry.settlement.repository.SettlementCalendarRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CaptureSettlementSchedule {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final SettlementCalendarRepository calendar;

    public Instant settlementAt(Instant capturedAt) {
        if (capturedAt == null) throw new IllegalArgumentException("매입 완료 시각이 필요합니다.");
        LocalDate date = capturedAt.atZone(SEOUL).toLocalDate();
        requireDay(date);
        int remaining = 3;
        while (remaining > 0) {
            date = date.plusDays(1);
            if (requireDay(date)) remaining--;
        }
        // 지급 실행 시각과 날짜 계산을 분리한다. 실제 완료 시각은 배치가 기록한다.
        return date.atStartOfDay(SEOUL).toInstant();
    }

    private boolean requireDay(LocalDate date) {
        return calendar.findBusinessDay(date).orElseThrow(() -> new IllegalStateException("정산 영업일 달력이 없습니다: " + date));
    }
}
