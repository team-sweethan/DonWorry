package com.donworry.fds.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "fds_rule_policy_change_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsRulePolicyChangeHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long rulePolicyChangeHistoryId;

    @Column(nullable = false, length = 100)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Source source;

    private Long requestedByUserId;

    @Column(length = 100)
    private String requestedByName;

    @Column(length = 500)
    private String requestReason;

    @Column(nullable = false)
    private Long changedByUserId;

    @Column(nullable = false, length = 100)
    private String changedByName;

    @Column(nullable = false)
    private Instant changedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private FdsRulePolicyChangeHistory(
            String ruleCode,
            Action action,
            Source source,
            Long requestedByUserId,
            String requestedByName,
            String requestReason,
            Long changedByUserId,
            String changedByName,
            Instant changedAt) {
        this.ruleCode = ruleCode;
        this.action = action;
        this.source = source;
        this.requestedByUserId = requestedByUserId;
        this.requestedByName = requestedByName;
        this.requestReason = requestReason;
        this.changedByUserId = changedByUserId;
        this.changedByName = changedByName;
        this.changedAt = changedAt;
    }

    public static FdsRulePolicyChangeHistory direct(
            String ruleCode,
            boolean active,
            Long actorUserId,
            String actorName,
            Instant changedAt,
            boolean securityStaff) {
        return builder()
                .ruleCode(ruleCode)
                .action(active ? Action.ENABLED : Action.DISABLED)
                .source(securityStaff ? Source.SECURITY_STAFF_DIRECT : Source.ADMIN_DIRECT)
                .changedByUserId(actorUserId)
                .changedByName(actorName)
                .changedAt(changedAt)
                .build();
    }

    public static FdsRulePolicyChangeHistory approvedDisable(
            FdsRuleChangeRequest request, Long actorUserId, String actorName, Instant changedAt) {
        return builder()
                .ruleCode(request.getRuleCode())
                .action(Action.DISABLED)
                .source(Source.DISABLE_REQUEST_APPROVED)
                .requestedByUserId(request.getRequestedByUserId())
                .requestedByName(request.getRequestedByName())
                .requestReason(request.getReason())
                .changedByUserId(actorUserId)
                .changedByName(actorName)
                .changedAt(changedAt)
                .build();
    }

    public enum Action {
        ENABLED,
        DISABLED
    }

    public enum Source {
        ADMIN_DIRECT,
        SECURITY_STAFF_DIRECT,
        DISABLE_REQUEST_APPROVED
    }
}
