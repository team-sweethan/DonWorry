package com.donworry.fds.service;

import com.donworry.fds.api.FdsRuleCatalogEntry;
import com.donworry.fds.dto.OperatorAccessDetectionResponses;
import com.donworry.fds.repository.OperatorAccessDetectionEvidenceRepository;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import com.donworry.fds.repository.OperatorAccessEventRepository;
import com.donworry.identity.api.MerchantDirectoryReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
    public OperatorAccessDetectionResponses.Page list(String risk, String search, int page, int size) {
        var merchantNames = merchants.findAll().stream()
                .collect(Collectors.toMap(value -> value.merchantId(), value -> value.displayName()));
        var high = detections.findLatestSignals().stream()
                .map(value -> highSignal(value, merchantNames))
                .toList();
        var low = events.findLatestLowRiskSignals().stream()
                .map(value -> lowSignal(value, merchantNames))
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
        var from = Math.min(page * size, filtered.size());
        var to = Math.min(from + size, filtered.size());
        return new OperatorAccessDetectionResponses.Page(
                filtered.subList(from, to),
                page,
                size,
                filtered.size(),
                (int) Math.ceil((double) filtered.size() / size),
                high.size(),
                low.size());
    }

    private OperatorAccessDetectionResponses.Signal highSignal(
            com.donworry.fds.repository.OperatorAccessHighRiskSignalProjection value, Map<Long, String> merchantNames) {
        return new OperatorAccessDetectionResponses.Signal(
                value.getDetectionId(),
                null,
                value.getLoginId(),
                affiliation(value.getMerchantId(), merchantNames),
                ruleName(value.getRuleCode()),
                "HIGH",
                value.getEventCount(),
                value.getActionStatus(),
                value.getOccurredAt());
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
                ruleName(code),
                "LOW",
                value.getEventCount(),
                "NONE",
                value.getOccurredAt());
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
