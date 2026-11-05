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
            String ruleName,
            String riskLevel,
            int eventCount,
            String actionStatus,
            Instant occurredAt) {}

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
