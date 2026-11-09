package com.donworry.fds.service;

import com.donworry.fds.api.FdsRuleCatalogEntry;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.repository.InsiderAlertRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 탐지 룰 목록의 계정 룰 탭 — 내부자 규칙 카드 (#685, ADR-0082)
// 기준 문구는 탐지 설정값으로 만든다 — 설정을 바꿔도 카드와 실제 기준이 어긋나지 않게
// 최근 7일 적중 = 규칙별로 그 기간에 새로 열린 경보 수. 날짜 칸은 사장님 계정 규칙 카드와 같이 UTC 기준 7칸
@Service
@RequiredArgsConstructor
public class InsiderRuleCatalogService {
    private static final String CATEGORY = "직원 감시";
    private static final int DAYS = 7;

    private final InsiderAlertRepository alerts;
    private final InsiderDetectionProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<FdsRuleCatalogEntry> ruleCatalog() {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate firstDay = today.minusDays(DAYS - 1);
        Map<InsiderRule, List<InsiderAlert>> opened =
                alerts.findByCreatedAtGreaterThanEqual(firstDay.atStartOfDay().toInstant(ZoneOffset.UTC)).stream()
                        .collect(Collectors.groupingBy(InsiderAlert::getRuleCode));
        return List.of(
                card(InsiderRule.INS_01, opened, firstDay)
                        .condition("%s 안에 담당 밖 사건 %d곳 이상에서 접근 거부"
                                .formatted(span(properties.ins01Window()), properties.ins01CautionCases()))
                        .resultDescription("주의 경보")
                        .example("배정되지 않은 사건 %d곳을 %s 안에 차례로 열려다 막힘"
                                .formatted(properties.ins01CautionCases(), span(properties.ins01Window())))
                        .standaloneDescription("같은 사건을 다시 연 것은 한 번으로 셉니다. 닫힌 사건 거부는 세지 않습니다.")
                        .build(),
                card(InsiderRule.INS_03, opened, firstDay)
                        .condition("%s 안에 주문자 전체 전화번호 %d건 이상 열람"
                                .formatted(span(properties.ins03Window()), properties.ins03CautionRawViews()))
                        .resultDescription("주의 경보")
                        .example("%s 동안 서로 다른 결제 %d건의 주문자 전체 전화번호를 엶"
                                .formatted(span(properties.ins03Window()), properties.ins03CautionRawViews()))
                        .standaloneDescription("전체 전화번호 보기는 사건 담당자만 할 수 있고, 열 때마다 기록됩니다.")
                        .build(),
                card(InsiderRule.INS_05, opened, firstDay)
                        .condition("높음 이상 경보가 열린 상태에서 거래 내역 내보내기 시도")
                        .resultDescription("내보내기 차단 · 계정 정지 (심각 경보)")
                        .example("대량 조회 높음 경보가 열린 직원이 거래 내역 CSV 내보내기를 누름")
                        .standaloneDescription("마지막 방어 장치라 끌 수 없습니다. 주의 경보만 열려 있으면 막지 않습니다.")
                        .build(),
                card(InsiderRule.INS_06, opened, firstDay)
                        .condition("서로 다른 종류의 경보 2개 이상이 함께 열림 (SEC-INS-08 · 09는 한 종류로 셈)")
                        .resultDescription("높음 경보 · 내부자 사건으로 넘어감")
                        .example("전화번호 열람 집중 주의와 담당 밖 접근 주의가 같은 직원에게 함께 열림")
                        .standaloneDescription("다른 규칙의 경보로만 생깁니다. 끄면 경보가 겹쳐도 높음으로 올리지 않습니다.")
                        .build(),
                card(InsiderRule.INS_07, opened, firstDay)
                        .condition("지난 %d일 성공 로그인에 없던 브라우저로 로그인하고 %s 안에 전화번호 열람"
                                .formatted(properties.ins07Lookback().toDays(), span(properties.ins07AfterLogin())))
                        .resultDescription("높음 경보")
                        .example("평소 Edge · Windows로 일하던 직원 계정이 Chrome · macOS로 로그인해 5분 뒤 전화번호 열람")
                        .standaloneDescription("비교할 지난 로그인이 없는 신규 직원에게는 울리지 않습니다.")
                        .build(),
                card(InsiderRule.INS_08, opened, firstDay)
                        .condition("%s 안에 거래 목록 · 내보내기 %,d행 이상, 담당 사건과 이어진 행 %d%% 미만 (%,d행부터 높음)"
                                .formatted(
                                        span(properties.ins08Window()),
                                        properties.ins08CautionRows(),
                                        Math.round(properties.ins08MaxLinkedRatio() * 100),
                                        properties.ins08HighRows()))
                        .resultDescription("주의 경보 · 많으면 높음 경보")
                        .example("담당 사건과 상관없는 가맹점 거래 %,d행을 %s 안에 조회 · 내보내기"
                                .formatted(properties.ins08CautionRows(), span(properties.ins08Window())))
                        .standaloneDescription("담당 사건 가맹점의 거래를 여러 쪽 넘겨 본 것은 세지 않습니다.")
                        .build(),
                card(InsiderRule.INS_09, opened, firstDay)
                        .condition("%s 안에 담당 사건과 무관한 고객 %d명 이상 검색 (%d명부터 높음)"
                                .formatted(
                                        span(properties.ins09Window()),
                                        properties.ins09CautionCustomers(),
                                        properties.ins09HighCustomers()))
                        .resultDescription("주의 경보 · 많으면 높음 경보")
                        .example("%s 동안 서로 다른 고객 번호 %d명을 거래 목록에서 검색"
                                .formatted(span(properties.ins09Window()), properties.ins09CautionCustomers()))
                        .standaloneDescription("같은 고객을 여러 쪽 넘긴 것은 1명으로 셉니다.")
                        .build());
    }

    private static FdsRuleCatalogEntry.FdsRuleCatalogEntryBuilder card(
            InsiderRule rule, Map<InsiderRule, List<InsiderAlert>> opened, LocalDate firstDay) {
        List<InsiderAlert> matched = opened.getOrDefault(rule, List.of());
        List<Long> daily = new ArrayList<>(Collections.nCopies(DAYS, 0L));
        for (InsiderAlert alert : matched) {
            int day = (int) ChronoUnit.DAYS.between(
                    firstDay, alert.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate());
            if (day >= 0 && day < DAYS) daily.set(day, daily.get(day) + 1);
        }
        Instant last = matched.stream()
                .map(InsiderAlert::getCreatedAt)
                .max(Instant::compareTo)
                .orElse(null);
        return FdsRuleCatalogEntry.builder()
                .ruleCode(rule.name())
                .category(CATEGORY)
                .ruleName(rule.label())
                .immediateDeny(false)
                .recentMatchCount(matched.size())
                .lastMatchedAt(last)
                .dailyMatchCounts(List.copyOf(daily));
    }

    // 10m → 10분, 1h → 1시간
    private static String span(Duration duration) {
        long minutes = duration.toMinutes();
        return minutes % 60 == 0 ? (minutes / 60) + "시간" : minutes + "분";
    }
}
