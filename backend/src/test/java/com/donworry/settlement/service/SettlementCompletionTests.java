package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementCompletionTests {
    // 확정 가능 시각 경계에서 완료되며 반복 실행은 원래 완료 시각을 보존한다
    @Test
    void evaluate_whenCalendarChanges_completesOnceAtStoredSchedule() {
        var at = Instant.parse("2026-10-27T00:00:00Z");
        var capture = Capture.processing(1L, 2L, "ORDER", 10000L, at);
        capture.complete(at);
        ReflectionTestUtils.setField(capture, "captureId", 3L);
        var s = Settlement.scheduled(
                3L, 1L, 2L, 10000L, new BigDecimal("0.02"), new BigDecimal("0.01"), 200L, 100L, 9700L, at);
        // 현재 달력으로 재계산한 날짜와 달라도 저장 당시 확정한 일정을 따른다.
        var due = Instant.parse("2026-11-01T15:00:00Z");
        s.applyCaptureSchedule(due, at);
        var policy = new SettlementCompletionPolicy();
        assertThat(policy.evaluate(s, capture, due.minusNanos(1))).isEqualTo("NOT_DUE");
        assertThat(policy.evaluate(s, capture, due)).isEqualTo("ELIGIBLE");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
        s.complete(due);
        s.complete(due.plusSeconds(1));
        assertThat(s.getCompletedAt()).isEqualTo(due);
        assertThat(s.getSettlementAmount()).isEqualTo(9700L);
    }
}
