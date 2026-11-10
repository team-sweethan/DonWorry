package com.donworry.fds.service;

import com.donworry.fds.api.FdsRuleCatalogEntry;
import com.donworry.fds.dto.OperatorAccessDetectionResponses;
import com.donworry.fds.repository.OperatorAccessDailyCountProjection;
import com.donworry.fds.repository.OperatorAccessDetectionEvidenceRepository;
import com.donworry.fds.repository.OperatorAccessDetectionPathCountProjection;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import com.donworry.fds.repository.OperatorAccessEventRepository;
import com.donworry.identity.api.MerchantDirectoryReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OperatorAccessDetectionQueryService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int SUMMARY_DAYS = 7;
    private static final int GROUP_SIGNAL_LIMIT = 5;
    private static final List<RuleDefinition> RULES = List.of(
            new RuleDefinition(
                    "SEC_OPERATOR_OUT_OF_ROLE_REPEAT",
                    "접근 통제",
                    "역할 범위 밖 API 반복 호출",
                    "5분 내 5회",
                    "고위험 탐지 및 계정 일시 정지",
                    "OPERATOR가 관리자 전용 API를 반복 호출",
                    "탐지 기록과 근거 요청을 저장한 뒤 계정을 정지합니다."),
            new RuleDefinition(
                    "SEC_OPERATOR_PERMISSION_MISSING_REPEAT",
                    "접근 통제",
                    "권한 부족 API 반복 호출",
                    "1분 내 30회",
                    "고위험 탐지 및 계정 일시 정지",
                    "FINANCE_VIEW 없는 OPERATOR가 재무 API를 반복 호출",
                    "탐지 기록과 근거 요청을 저장한 뒤 계정을 정지합니다."));

    private final OperatorAccessDetectionRepository detections;
    private final OperatorAccessDetectionEvidenceRepository evidences;
    private final OperatorAccessEventRepository events;
    private final MerchantDirectoryReader merchants;

    @Transactional(readOnly = true)
    public OperatorAccessDetectionResponses.Page list(
            String risk, String rule, String loginId, String search, int page, int size) {
        var signals = signals(risk, rule, loginId, search);
        var filtered = signals.filtered();
        var from = Math.min(page * size, filtered.size());
        var to = Math.min(from + size, filtered.size());
        return new OperatorAccessDetectionResponses.Page(
                withBlockedPaths(filtered.subList(from, to)),
                page,
                size,
                filtered.size(),
                (int) Math.ceil((double) filtered.size() / size),
                signals.highCount(),
                signals.lowCount());
    }

    /** 계정별 보기 — 같은 조건으로 거른 탐지를 계정으로 묶고, 묶음 단위로 쪽을 나눈다 (최근 발생 계정이 위) */
    @Transactional(readOnly = true)
    public OperatorAccessDetectionResponses.AccountPage accounts(
            String risk, String rule, String loginId, String search, int page, int size) {
        var signals = signals(risk, rule, loginId, search);
        var groups = signals.filtered().stream()
                .collect(Collectors.groupingBy(
                        OperatorAccessDetectionResponses.Signal::loginId, LinkedHashMap::new, Collectors.toList()));
        var keys = List.copyOf(groups.keySet());
        var from = Math.min(page * size, keys.size());
        var to = Math.min(from + size, keys.size());
        var pageGroups = keys.subList(from, to).stream().map(groups::get).toList();
        var shown = withBlockedPaths(pageGroups.stream()
                .flatMap(rows -> rows.stream().limit(GROUP_SIGNAL_LIMIT))
                .toList());
        var shownByKey = shown.stream()
                .collect(Collectors.groupingBy(
                        OperatorAccessDetectionResponses.Signal::loginId, LinkedHashMap::new, Collectors.toList()));
        var content = pageGroups.stream()
                .map(rows -> {
                    var latest = rows.getFirst();
                    return new OperatorAccessDetectionResponses.AccountGroup(
                            latest.loginId(),
                            latest.affiliation(),
                            rows.stream().anyMatch(row -> "HIGH".equals(row.riskLevel())) ? "HIGH" : "LOW",
                            rows.size(),
                            rows.stream()
                                    .mapToLong(OperatorAccessDetectionResponses.Signal::eventCount)
                                    .sum(),
                            latest.occurredAt(),
                            shownByKey.getOrDefault(latest.loginId(), List.of()));
                })
                .toList();
        return new OperatorAccessDetectionResponses.AccountPage(
                content,
                page,
                size,
                keys.size(),
                (int) Math.ceil((double) keys.size() / size),
                signals.highCount(),
                signals.lowCount());
    }

    /**
     * 위험도 · 룰 · 계정 · 검색어로 거른 탐지(최근 것부터)와 탭 건수. 탭 건수는 룰 · 계정 거르기만 따르고 검색어는 따르지 않는다 (기존과 같음).
     * 계정은 로그인 ID 정확 일치 — 검색어(부분 일치)로 넘기면 boss · boss2가 섞인다
     */
    private Signals signals(String risk, String rule, String loginId, String search) {
        var merchantNames = merchants.findAll().stream()
                .collect(Collectors.toMap(value -> value.merchantId(), value -> value.displayName()));
        var ruleCode = rule == null || rule.isBlank() ? null : rule.trim();
        var account = loginId == null || loginId.isBlank() ? null : loginId.trim();
        java.util.function.Predicate<OperatorAccessDetectionResponses.Signal> matches =
                value -> (ruleCode == null || ruleCode.equals(value.ruleCode()))
                        && (account == null || account.equals(value.loginId()));
        var high = detections.findLatestSignals().stream()
                .map(value -> highSignal(value, merchantNames))
                .filter(matches)
                .toList();
        var low = events.findLatestLowRiskSignals().stream()
                .map(value -> lowSignal(value, merchantNames))
                .filter(matches)
                .toList();
        var all = new ArrayList<OperatorAccessDetectionResponses.Signal>();
        if (!"LOW".equals(risk)) all.addAll(high);
        if (!"HIGH".equals(risk)) all.addAll(low);
        var keyword = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        var filtered = all.stream()
                .filter(value -> keyword.isEmpty()
                        || (value.loginId() + " " + value.affiliation() + " " + value.ruleName())
                                .toLowerCase(Locale.ROOT)
                                .contains(keyword))
                .sorted(java.util.Comparator.comparing(OperatorAccessDetectionResponses.Signal::occurredAt)
                        .reversed())
                .toList();
        return new Signals(filtered, high.size(), low.size());
    }

    private record Signals(List<OperatorAccessDetectionResponses.Signal> filtered, long highCount, long lowCount) {}

    /**
     * 보이는 줄에만 막힌 경로를 채운다. 고위험은 근거 요청 중 가장 많이 막힌 경로, 저위험은 최신 신호 요청의 경로.
     * 줄 수와 상관없이 쿼리 두 번 (고위험 경로 집계 한 번 · 저위험 요청 조회 한 번)
     */
    private List<OperatorAccessDetectionResponses.Signal> withBlockedPaths(
            List<OperatorAccessDetectionResponses.Signal> rows) {
        var detectionIds = rows.stream()
                .map(OperatorAccessDetectionResponses.Signal::detectionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        var eventIds = rows.stream()
                .filter(row -> row.detectionId() == null)
                .map(OperatorAccessDetectionResponses.Signal::sourceEventId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        var highPaths = new HashMap<Long, OperatorAccessDetectionPathCountProjection>();
        if (!detectionIds.isEmpty()) {
            for (var row : evidences.countPathsByDetectionIds(detectionIds)) {
                highPaths.merge(
                        row.getDetectionId(),
                        row,
                        (current, next) -> next.getDeniedCount() > current.getDeniedCount()
                                        || (next.getDeniedCount() == current.getDeniedCount()
                                                && (next.getPath() + next.getHttpMethod())
                                                                .compareTo(current.getPath() + current.getHttpMethod())
                                                        < 0)
                                ? next
                                : current);
            }
        }
        var lowPaths = new HashMap<Long, String>();
        if (!eventIds.isEmpty()) {
            for (var event : events.findAllById(eventIds)) {
                lowPaths.put(event.getId(), event.getHttpMethod() + " " + event.getPath());
            }
        }
        return rows.stream()
                .map(row -> {
                    if (row.detectionId() != null) {
                        var hit = highPaths.get(row.detectionId());
                        return row.withBlockedPath(hit == null ? null : hit.getHttpMethod() + " " + hit.getPath());
                    }
                    return row.withBlockedPath(lowPaths.get(row.sourceEventId()));
                })
                .toList();
    }

    private OperatorAccessDetectionResponses.Signal highSignal(
            com.donworry.fds.repository.OperatorAccessHighRiskSignalProjection value, Map<Long, String> merchantNames) {
        return new OperatorAccessDetectionResponses.Signal(
                value.getDetectionId(),
                null,
                value.getLoginId(),
                affiliation(value.getMerchantId(), merchantNames),
                value.getRuleCode(),
                ruleName(value.getRuleCode()),
                "HIGH",
                value.getEventCount(),
                value.getActionStatus(),
                value.getReviewedAt(),
                value.getOccurredAt(),
                null);
    }

    private OperatorAccessDetectionResponses.Signal lowSignal(
            com.donworry.fds.repository.OperatorAccessLowRiskSignalProjection value, Map<Long, String> merchantNames) {
        var code = value.getDeniedType().equals("OUT_OF_ROLE")
                ? "SEC_OPERATOR_OUT_OF_ROLE_REPEAT"
                : "SEC_OPERATOR_PERMISSION_MISSING_REPEAT";
        return new OperatorAccessDetectionResponses.Signal(
                null,
                value.getSourceEventId(),
                value.getLoginId(),
                affiliation(value.getMerchantId(), merchantNames),
                code,
                ruleName(code),
                "LOW",
                value.getEventCount(),
                "NONE",
                null,
                value.getOccurredAt(),
                null);
    }

    @Transactional(readOnly = true)
    public OperatorAccessDetectionResponses.Summary summary() {
        var today = LocalDate.now(SEOUL);
        var firstDay = today.minusDays(SUMMARY_DAYS - 1);
        // 기록은 UTC로 저장된다. 한국 날짜 첫날 0시를 UTC 글자로 바꿔 넘긴다 (다른 native 쿼리와 같은 방식)
        var from = LocalDateTime.ofInstant(firstDay.atStartOfDay(SEOUL).toInstant(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
        var counts = events.countDailySince(from).stream()
                .collect(Collectors.toMap(
                        OperatorAccessDailyCountProjection::getDay,
                        OperatorAccessDailyCountProjection::getDeniedCount));
        var days = new ArrayList<OperatorAccessDetectionResponses.DailyCount>();
        for (var i = 0; i < SUMMARY_DAYS; i++) {
            var day = firstDay.plusDays(i);
            days.add(new OperatorAccessDetectionResponses.DailyCount(day, counts.getOrDefault(day, 0L)));
        }
        var topPaths = events.countPathsSince(from).stream()
                .map(row -> new OperatorAccessDetectionResponses.PathCount(
                        row.getHttpMethod(), row.getPath(), row.getDeniedCount()))
                .toList();
        return new OperatorAccessDetectionResponses.Summary(
                days.stream()
                        .mapToLong(OperatorAccessDetectionResponses.DailyCount::count)
                        .sum(),
                events.countAccountsSince(from),
                List.copyOf(days),
                topPaths);
    }

    private String affiliation(Long merchantId, Map<Long, String> merchantNames) {
        return merchantNames.getOrDefault(merchantId, "고객사 #" + merchantId);
    }

    private String ruleName(String code) {
        return RULES.stream()
                .filter(value -> value.code().equals(code))
                .findFirst()
                .map(value -> value.name())
                .orElse(code);
    }

    @Transactional(readOnly = true)
    public OperatorAccessDetectionResponses.Detail detail(Long detectionId) {
        var detection = detections
                .findById(detectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "탐지 기록을 찾을 수 없습니다."));
        return OperatorAccessDetectionResponses.Detail.from(
                detection, evidences.findByDetectionIdOrderByOccurredAtAsc(detectionId));
    }

    @Transactional
    public void review(Long detectionId) {
        var detection = detections
                .findById(detectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "탐지 기록을 찾을 수 없습니다."));
        detection.review(Instant.now());
    }

    @Transactional(readOnly = true)
    public long unreviewedCount() {
        return detections.countByReviewedAtIsNull();
    }

    @Transactional(readOnly = true)
    public OperatorAccessDetectionResponses.Detail lowRiskDetail(Long eventId) {
        var event = events.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "저위험 접근 기록을 찾을 수 없습니다."));
        Set<Long> highEvidenceIds = Set.copyOf(evidences.findAllIdentityEventIds());
        if (highEvidenceIds.contains(event.getIdentityEventId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "저위험 접근 기록을 찾을 수 없습니다.");
        }
        var windowSeconds =
                event.getDeniedType() == com.donworry.fds.entity.OperatorAccessDeniedType.OUT_OF_ROLE ? 300 : 60;
        var thresholdCount =
                event.getDeniedType() == com.donworry.fds.entity.OperatorAccessDeniedType.OUT_OF_ROLE ? 5 : 30;
        var windowStart = event.getOccurredAt().minusSeconds(windowSeconds);
        var matching = events
                .findByUserIdAndDeniedTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
                        event.getUserId(), event.getDeniedType(), windowStart, event.getOccurredAt())
                .stream()
                .filter(value -> !highEvidenceIds.contains(value.getIdentityEventId()))
                .toList();
        if (matching.size() >= thresholdCount) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "저위험 접근 기록을 찾을 수 없습니다.");
        }
        return OperatorAccessDetectionResponses.Detail.lowRisk(event, windowSeconds, thresholdCount, matching);
    }

    public List<FdsRuleCatalogEntry> ruleCatalog() {
        var today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        var start = today.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC);
        var frequencies = new HashMap<String, RuleFrequency>();
        for (var detection : detections.findByDetectedAtGreaterThanEqual(start)) {
            var dayIndex = (int) ChronoUnit.DAYS.between(
                    start.atZone(ZoneOffset.UTC).toLocalDate(),
                    detection.getDetectedAt().atZone(ZoneOffset.UTC).toLocalDate());
            frequencies
                    .computeIfAbsent(detection.getRuleCode(), ignored -> new RuleFrequency())
                    .add(dayIndex, detection.getDetectedAt());
        }
        return RULES.stream()
                .map(rule -> rule.toEntry(frequencies.get(rule.code())))
                .toList();
    }

    private record RuleDefinition(
            String code,
            String category,
            String name,
            String condition,
            String result,
            String example,
            String description) {
        private FdsRuleCatalogEntry toEntry(RuleFrequency frequency) {
            var resolved = frequency == null ? new RuleFrequency() : frequency;
            return new FdsRuleCatalogEntry(
                    code,
                    category,
                    name,
                    condition,
                    result,
                    example,
                    description,
                    false,
                    resolved.count,
                    resolved.lastMatchedAt,
                    List.copyOf(resolved.dailyCounts));
        }
    }

    private static final class RuleFrequency {
        private final List<Long> dailyCounts = new ArrayList<>(java.util.Collections.nCopies(7, 0L));
        private long count;
        private Instant lastMatchedAt;

        private void add(int dayIndex, Instant matchedAt) {
            count++;
            if (dayIndex >= 0 && dayIndex < dailyCounts.size()) {
                dailyCounts.set(dayIndex, dailyCounts.get(dayIndex) + 1);
            }
            if (lastMatchedAt == null || matchedAt.isAfter(lastMatchedAt)) lastMatchedAt = matchedAt;
        }
    }
}
