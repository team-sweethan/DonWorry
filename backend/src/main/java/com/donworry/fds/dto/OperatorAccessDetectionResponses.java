package com.donworry.fds.dto;

import com.donworry.fds.entity.OperatorAccessDetection;
import com.donworry.fds.entity.OperatorAccessDetectionEvidence;
import com.donworry.fds.entity.OperatorAccessEvent;
import java.time.Instant;
import java.util.List;

public final class OperatorAccessDetectionResponses {
    private OperatorAccessDetectionResponses() {}

    public record Signal(
            Long detectionId,
            Long sourceEventId,
            String loginId,
            String affiliation,
            String ruleCode,
            String ruleName,
            String riskLevel,
            int eventCount,
            String actionStatus,
            Instant occurredAt,
            String blockedPath) {
        /** 목록 한 쪽에 들어간 줄만 막힌 경로("GET /api/admin/users")를 채운다 */
        public Signal withBlockedPath(String value) {
            return new Signal(
                    detectionId,
                    sourceEventId,
                    loginId,
                    affiliation,
                    ruleCode,
                    ruleName,
                    riskLevel,
                    eventCount,
                    actionStatus,
                    occurredAt,
                    value);
        }
    }

    /** 목록 위 요약 — 최근 7일(한국 날짜) 거부 요청 수 · 날짜별 수 · 거부된 계정 수 · 많이 막힌 경로 */
    public record Summary(long deniedCount, long accountCount, List<DailyCount> daily, List<PathCount> topPaths) {}

    public record DailyCount(java.time.LocalDate date, long count) {}

    public record PathCount(String httpMethod, String path, long count) {}

    /** 계정별 보기의 한 묶음 — 같은 계정의 탐지. signals는 최근 것부터 최대 몇 건만 담고, 전체 건수는 detectionCount */
    public record AccountGroup(
            String loginId,
            String affiliation,
            String riskLevel,
            int detectionCount,
            long eventCount,
            Instant lastOccurredAt,
            List<Signal> signals) {}

    public record AccountPage(
            List<AccountGroup> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            long highRiskCount,
            long lowRiskCount) {}

    public record Page(
            List<Signal> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            long highRiskCount,
            long lowRiskCount) {}

    public record Detail(
            Long detectionId,
            String riskLevel,
            Long userId,
            String loginId,
            Long merchantId,
            String ruleCode,
            String deniedType,
            int windowSeconds,
            int thresholdCount,
            int eventCount,
            Instant windowStartedAt,
            Instant windowEndedAt,
            Instant lastActivatedAt,
            String actionStatus,
            Instant actionCompletedAt,
            String failureCode,
            Instant detectedAt,
            List<Evidence> evidences) {
        public static Detail from(OperatorAccessDetection value, List<OperatorAccessDetectionEvidence> evidences) {
            return new Detail(
                    value.getId(),
                    "HIGH",
                    value.getUserId(),
                    value.getLoginId(),
                    value.getMerchantId(),
                    value.getRuleCode(),
                    value.getDeniedType().name(),
                    value.getWindowSeconds(),
                    value.getThresholdCount(),
                    value.getEventCount(),
                    value.getWindowStartedAt(),
                    value.getWindowEndedAt(),
                    value.getLastActivatedAt(),
                    value.getActionStatus().name(),
                    value.getActionCompletedAt(),
                    value.getFailureCode(),
                    value.getDetectedAt(),
                    evidences.stream().map(Evidence::from).toList());
        }

        public static Detail lowRisk(
                OperatorAccessEvent event, int windowSeconds, int thresholdCount, List<OperatorAccessEvent> evidences) {
            return new Detail(
                    null,
                    "LOW",
                    event.getUserId(),
                    event.getLoginId(),
                    event.getMerchantId(),
                    event.getDeniedType() == com.donworry.fds.entity.OperatorAccessDeniedType.OUT_OF_ROLE
                            ? "SEC_OPERATOR_OUT_OF_ROLE_REPEAT"
                            : "SEC_OPERATOR_PERMISSION_MISSING_REPEAT",
                    event.getDeniedType().name(),
                    windowSeconds,
                    thresholdCount,
                    evidences.size(),
                    event.getOccurredAt().minusSeconds(windowSeconds),
                    event.getOccurredAt(),
                    null,
                    "NONE",
                    null,
                    null,
                    event.getOccurredAt(),
                    evidences.stream().map(Evidence::fromEvent).toList());
        }
    }

    public record Evidence(Long identityEventId, String httpMethod, String path, Instant occurredAt) {
        private static Evidence from(OperatorAccessDetectionEvidence value) {
            return new Evidence(
                    value.getIdentityEventId(),
                    value.getEvent().getHttpMethod(),
                    value.getEvent().getPath(),
                    value.getOccurredAt());
        }

        private static Evidence fromEvent(OperatorAccessEvent value) {
            return new Evidence(
                    value.getIdentityEventId(), value.getHttpMethod(), value.getPath(), value.getOccurredAt());
        }
    }
}
