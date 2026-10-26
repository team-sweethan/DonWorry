package com.donworry.fds.entity;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleHitOutcome;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "fds_evaluations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "evaluation_id")
    private Long evaluationId;

    @Column(name = "evaluation_request_id", nullable = false, unique = true)
    private UUID evaluationRequestId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "customer_ref", nullable = false, length = 100)
    private String customerRef;

    @Column(name = "payment_requested_at", nullable = false)
    private Instant paymentRequestedAt;

    @Column(name = "requested_amount", nullable = false)
    private Long requestedAmount;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FdsResult result;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<FdsRuleHit> ruleHits = new ArrayList<>();

    @Builder(access = AccessLevel.PRIVATE)
    private FdsEvaluation(
            UUID evaluationRequestId,
            Long paymentId,
            String customerRef,
            Instant paymentRequestedAt,
            Long requestedAmount,
            Integer riskScore,
            FdsResult result,
            Instant evaluatedAt,
            String errorCode) {
        this.evaluationRequestId = evaluationRequestId;
        this.paymentId = paymentId;
        this.customerRef = customerRef;
        this.paymentRequestedAt = paymentRequestedAt;
        this.requestedAmount = requestedAmount;
        this.riskScore = riskScore;
        this.result = result;
        this.evaluatedAt = evaluatedAt;
        this.errorCode = errorCode;
    }

    public static FdsEvaluation completed(
            FdsEvaluationRequest request, FdsResult result, int riskScore, Instant evaluatedAt) {
        return FdsEvaluation.builder()
                .evaluationRequestId(request.evaluationRequestId())
                .paymentId(request.paymentId())
                .customerRef(request.customerRef())
                .paymentRequestedAt(request.paymentRequestedAt().truncatedTo(ChronoUnit.MICROS))
                .requestedAmount(request.amount())
                .riskScore(riskScore)
                .result(result)
                .evaluatedAt(evaluatedAt)
                .build();
    }

    public boolean hasSameRequestSnapshot(FdsEvaluationRequest request) {
        return paymentId.equals(request.paymentId())
                && customerRef.equals(request.customerRef())
                && requestedAmount == request.amount()
                && paymentRequestedAt.equals(request.paymentRequestedAt().truncatedTo(ChronoUnit.MICROS));
    }

    public void addRuleHit(String ruleCode, FdsRuleHitOutcome outcome, int appliedScore, String ruleNameSnapshot) {
        addRuleHit(ruleCode, outcome, appliedScore, ruleNameSnapshot, null, null, null, null);
    }

    public void addRuleHit(
            String ruleCode,
            FdsRuleHitOutcome outcome,
            int appliedScore,
            String ruleNameSnapshot,
            String thresholdSnapshot,
            String observedValue,
            Instant windowStart,
            Instant windowEnd) {
        ruleHits.add(FdsRuleHit.create(
                this,
                ruleCode,
                outcome,
                appliedScore,
                ruleNameSnapshot,
                thresholdSnapshot,
                observedValue,
                windowStart,
                windowEnd));
    }
}
