package com.donworry.fds.entity;

import com.donworry.fds.api.FdsRuleHitOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "fds_rule_hits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsRuleHit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "hit_id")
    private Long hitId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private FdsEvaluation evaluation;

    @Column(name = "rule_code", nullable = false, length = 100)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FdsRuleHitOutcome outcome;

    @Column(name = "applied_score", nullable = false)
    private int appliedScore;

    @Column(name = "rule_name_snapshot", nullable = false, length = 200)
    private String ruleNameSnapshot;

    @Column(name = "threshold_snapshot")
    private String thresholdSnapshot;

    @Column(name = "observed_value")
    private String observedValue;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Builder(access = AccessLevel.PRIVATE)
    private FdsRuleHit(
            FdsEvaluation evaluation,
            String ruleCode,
            FdsRuleHitOutcome outcome,
            int appliedScore,
            String ruleNameSnapshot,
            String thresholdSnapshot,
            String observedValue,
            Instant windowStart,
            Instant windowEnd) {
        this.evaluation = evaluation;
        this.ruleCode = ruleCode;
        this.outcome = outcome;
        this.appliedScore = appliedScore;
        this.ruleNameSnapshot = ruleNameSnapshot;
        this.thresholdSnapshot = thresholdSnapshot;
        this.observedValue = observedValue;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    static FdsRuleHit create(
            FdsEvaluation evaluation,
            String ruleCode,
            FdsRuleHitOutcome outcome,
            int appliedScore,
            String ruleNameSnapshot,
            String thresholdSnapshot,
            String observedValue,
            Instant windowStart,
            Instant windowEnd) {
        return FdsRuleHit.builder()
                .evaluation(evaluation)
                .ruleCode(ruleCode)
                .outcome(outcome)
                .appliedScore(appliedScore)
                .ruleNameSnapshot(ruleNameSnapshot)
                .thresholdSnapshot(thresholdSnapshot)
                .observedValue(observedValue)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .build();
    }

    public boolean backfillSnapshotsIfMissing(
            String thresholdSnapshot, String observedValue, Instant windowStart, Instant windowEnd) {
        var changed = false;
        if (this.thresholdSnapshot == null) {
            this.thresholdSnapshot = thresholdSnapshot;
            changed = true;
        }
        if (this.observedValue == null) {
            this.observedValue = observedValue;
            changed = true;
        }
        if (this.windowStart == null && windowStart != null) {
            this.windowStart = windowStart;
            changed = true;
        }
        if (this.windowEnd == null && windowEnd != null) {
            this.windowEnd = windowEnd;
            changed = true;
        }
        return changed;
    }
}
