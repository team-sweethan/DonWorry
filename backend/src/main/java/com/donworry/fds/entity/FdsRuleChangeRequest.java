package com.donworry.fds.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "fds_rule_change_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsRuleChangeRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long ruleChangeRequestId;

    @Column(nullable = false, length = 10)
    private String ruleCode;

    @Column(nullable = false)
    private Long requestedByUserId;

    @Column(nullable = false, length = 100)
    private String requestedByName;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    private Long reviewedByUserId;

    @Column(length = 100)
    private String reviewedByName;

    private Instant reviewedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private FdsRuleChangeRequest(
            String ruleCode, Long requestedByUserId, String requestedByName, String reason, Instant requestedAt) {
        this.ruleCode = ruleCode;
        this.requestedByUserId = requestedByUserId;
        this.requestedByName = requestedByName;
        this.reason = reason;
        this.requestedAt = requestedAt;
        this.status = Status.PENDING;
    }

    public static FdsRuleChangeRequest disable(
            String ruleCode, Long requestedByUserId, String requestedByName, String reason, Instant requestedAt) {
        return FdsRuleChangeRequest.builder()
                .ruleCode(ruleCode)
                .requestedByUserId(requestedByUserId)
                .requestedByName(requestedByName)
                .reason(reason)
                .requestedAt(requestedAt)
                .build();
    }

    public void review(Status status, Long reviewerUserId, String reviewerName, Instant reviewedAt) {
        if (this.status != Status.PENDING || (status != Status.APPROVED && status != Status.REJECTED)) {
            throw new IllegalStateException("처리할 수 없는 룰 끄기 요청입니다.");
        }
        this.status = status;
        this.reviewedByUserId = reviewerUserId;
        this.reviewedByName = reviewerName;
        this.reviewedAt = reviewedAt;
    }

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }
}
