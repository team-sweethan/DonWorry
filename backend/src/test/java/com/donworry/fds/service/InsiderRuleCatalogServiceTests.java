package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsRuleCatalogEntry;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.repository.InsiderAlertRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

// 탐지 룰 목록의 내부자 규칙 카드 (#685) — 승인된 테스트 목록만 (노트 issue-685)
class InsiderRuleCatalogServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-08T05:00:00Z");

    private final InsiderAlertRepository alerts = mock(InsiderAlertRepository.class);
    private final InsiderDetectionProperties properties = InsiderDetectionProperties.builder()
            .ins01Window(Duration.ofMinutes(10))
            .ins01CautionCases(3)
            .ins03Window(Duration.ofHours(1))
            .ins03CautionRawViews(3)
            .ins07AfterLogin(Duration.ofMinutes(30))
            .ins07Lookback(Duration.ofDays(30))
            .ins10Lookback(Duration.ofDays(30))
            .ins08Window(Duration.ofHours(1))
            .ins08CautionRows(1000)
            .ins08HighRows(3000)
            .ins08MaxLinkedRatio(0.3)
            .ins09Window(Duration.ofHours(1))
            .ins09CautionCustomers(10)
            .ins09HighCustomers(30)
            .scopeClosedLookback(Duration.ofDays(30))
            .build();
    private final InsiderRuleCatalogService service =
            new InsiderRuleCatalogService(alerts, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    // 7일 적중 수가 다른 규칙 것과 섞이거나 기간 경계가 틀리면, 관리자가 규칙을 끌지 말지 판단할 근거가 틀어진다
    // 기간 = 오늘 포함 7일(UTC 11/02 00:00부터). 그 전에 열린 경보는 빠진다
    @Test
    void insiderRuleCatalog_countsAlertsOpenedInLastSevenDaysPerRule() {
        List<InsiderAlert> stored = List.of(
                opened(InsiderRule.INS_03, "2026-11-01T23:59:59Z"),
                opened(InsiderRule.INS_03, "2026-11-02T00:00:00Z"),
                opened(InsiderRule.INS_03, "2026-11-08T01:00:00Z"),
                opened(InsiderRule.INS_01, "2026-11-05T12:00:00Z"));
        when(alerts.findByCreatedAtGreaterThanEqual(any())).thenAnswer(invocation -> {
            Instant from = invocation.getArgument(0);
            return stored.stream().filter(a -> !a.getCreatedAt().isBefore(from)).toList();
        });

        Map<String, FdsRuleCatalogEntry> cards = service.ruleCatalog().stream()
                .collect(Collectors.toMap(FdsRuleCatalogEntry::ruleCode, Function.identity()));

        assertThat(cards)
                .containsOnlyKeys("INS_01", "INS_03", "INS_05", "INS_06", "INS_07", "INS_08", "INS_09", "INS_10");
        assertThat(cards.get("INS_03").recentMatchCount()).isEqualTo(2);
        assertThat(cards.get("INS_03").dailyMatchCounts()).containsExactly(1L, 0L, 0L, 0L, 0L, 0L, 1L);
        assertThat(cards.get("INS_03").lastMatchedAt()).isEqualTo(Instant.parse("2026-11-08T01:00:00Z"));
        assertThat(cards.get("INS_01").recentMatchCount()).isEqualTo(1);
        assertThat(cards.get("INS_01").dailyMatchCounts()).containsExactly(0L, 0L, 0L, 1L, 0L, 0L, 0L);
        assertThat(cards.get("INS_05").recentMatchCount()).isZero();
        assertThat(cards.get("INS_05").lastMatchedAt()).isNull();
    }

    private static InsiderAlert opened(InsiderRule rule, String at) {
        Instant openedAt = Instant.parse(at);
        return InsiderAlert.open(4L, rule, InsiderAlertSeverity.CAUTION, "요약", openedAt, openedAt, openedAt);
    }
}
