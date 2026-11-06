package com.donworry.fds.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "fds_operator_access_detections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatorAccessDetection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operator_access_detection_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "login_id", nullable = false, length = 100)
    private String loginId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "rule_code", nullable = false, length = 100)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "denied_type", nullable = false, length = 50)
    private OperatorAccessDeniedType deniedType;

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds;

    @Column(name = "threshold_count", nullable = false)
    private int thresholdCount;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "window_ended_at", nullable = false)
    private Instant windowEndedAt;

    @Column(name = "last_activated_at")
    private Instant lastActivatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_status", nullable = false, length = 50)
    private OperatorAccessDetectionActionStatus actionStatus;

    @Column(name = "action_completed_at")
    private Instant actionCompletedAt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private OperatorAccessDetection(
            Long userId,
            String loginId,
            Long merchantId,
            String ruleCode,
            OperatorAccessDeniedType deniedType,
            int windowSeconds,
            int thresholdCount,
            int eventCount,
            Instant windowStartedAt,
            Instant windowEndedAt,
            Instant lastActivatedAt,
            Instant detectedAt) {
        this.userId = userId;
        this.loginId = loginId;
        this.merchantId = merchantId;
        this.ruleCode = ruleCode;
        this.deniedType = deniedType;
        this.windowSeconds = windowSeconds;
        this.thresholdCount = thresholdCount;
        this.eventCount = eventCount;
        this.windowStartedAt = windowStartedAt;
        this.windowEndedAt = windowEndedAt;
        this.lastActivatedAt = lastActivatedAt;
        this.actionStatus = OperatorAccessDetectionActionStatus.REQUESTED;
        this.detectedAt = detectedAt;
        this.createdAt = detectedAt;
        this.updatedAt = detectedAt;
    }

    public static OperatorAccessDetection detected(
            OperatorAccessEvent event,
            String ruleCode,
            int windowSeconds,
            int thresholdCount,
            int eventCount,
            Instant windowStartedAt,
            Instant windowEndedAt,
            Instant lastActivatedAt,
            Instant detectedAt) {
        return builder()
                .userId(event.getUserId())
                .loginId(event.getLoginId())
                .merchantId(event.getMerchantId())
                .ruleCode(ruleCode)
                .deniedType(event.getDeniedType())
                .windowSeconds(windowSeconds)
                .thresholdCount(thresholdCount)
                .eventCount(eventCount)
                .windowStartedAt(windowStartedAt)
                .windowEndedAt(windowEndedAt)
                .lastActivatedAt(lastActivatedAt)
                .detectedAt(detectedAt)
                .build();
    }

    public void complete(OperatorAccessDetectionActionStatus status, String failureCode, Instant completedAt) {
        this.actionStatus = status;
        this.failureCode = failureCode;
        this.actionCompletedAt = completedAt;
        this.updatedAt = completedAt;
    }
}
