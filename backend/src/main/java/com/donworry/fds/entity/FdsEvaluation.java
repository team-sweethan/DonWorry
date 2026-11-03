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

    @Column(name = "original_evaluation_request_id")
    private UUID originalEvaluationRequestId;

    @Column(name = "reevaluation_retry_id", unique = true)
    private UUID reevaluationRetryId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "customer_ref", nullable = false, length = 100)
    private String customerRef;

    @Column(name = "device_id", length = 100)
    private String deviceId;

    @Column(name = "card_token", length = 255)
    private String cardToken;

    @Column(name = "card_display_label", length = 50)
    private String cardDisplayLabel;

    @Column(name = "ip_country_code", length = 2)
    private String ipCountryCode;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "buyer_email", length = 254)
    private String buyerEmail;

    @Column(name = "authorization_result", length = 20)
    private String authorizationResult;

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
            Long merchantId,
            String customerRef,
            String deviceId,
            String cardToken,
            String cardDisplayLabel,
            String ipCountryCode,
            String ipAddress,
            String buyerEmail,
            Instant paymentRequestedAt,
            Long requestedAmount,
            Integer riskScore,
            FdsResult result,
            Instant evaluatedAt,
            String errorCode) {
        this.evaluationRequestId = evaluationRequestId;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.customerRef = customerRef;
        this.deviceId = deviceId;
        this.cardToken = cardToken;
        this.cardDisplayLabel = cardDisplayLabel;
        this.ipCountryCode = ipCountryCode;
        this.ipAddress = ipAddress;
        this.buyerEmail = buyerEmail;
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
                .merchantId(request.merchantId())
                .customerRef(request.customerRef())
                .deviceId(request.deviceId())
                .cardToken(request.cardToken())
                .cardDisplayLabel(request.cardDisplayLabel())
                .ipCountryCode(request.ipCountryCode())
                .ipAddress(request.ipAddress())
                .buyerEmail(request.buyerEmail())
                .paymentRequestedAt(request.paymentRequestedAt().truncatedTo(ChronoUnit.MICROS))
                .requestedAmount(request.amount())
                .riskScore(riskScore)
                .result(result)
                .evaluatedAt(evaluatedAt)
                .build();
    }

    public static FdsEvaluation failed(FdsEvaluationRequest request, String errorCode, Instant evaluatedAt) {
        if (errorCode == null || errorCode.isBlank()) throw new IllegalArgumentException("errorCode must not be blank");
        return FdsEvaluation.builder()
                .evaluationRequestId(request.evaluationRequestId())
                .paymentId(request.paymentId())
                .merchantId(request.merchantId())
                .customerRef(request.customerRef())
                .deviceId(request.deviceId())
                .cardToken(request.cardToken())
                .cardDisplayLabel(request.cardDisplayLabel())
                .ipCountryCode(request.ipCountryCode())
                .ipAddress(request.ipAddress())
                .buyerEmail(request.buyerEmail())
                .paymentRequestedAt(request.paymentRequestedAt().truncatedTo(ChronoUnit.MICROS))
                .requestedAmount(request.amount())
                .result(FdsResult.ERROR)
                .evaluatedAt(evaluatedAt)
                .errorCode(errorCode)
                .build();
    }

    public boolean hasSameRequestSnapshot(FdsEvaluationRequest request) {
        return paymentId.equals(request.paymentId())
                && merchantId.equals(request.merchantId())
                && customerRef.equals(request.customerRef())
                && java.util.Objects.equals(deviceId, request.deviceId())
                && java.util.Objects.equals(cardToken, request.cardToken())
                && java.util.Objects.equals(cardDisplayLabel, request.cardDisplayLabel())
                && java.util.Objects.equals(ipCountryCode, request.ipCountryCode())
                && java.util.Objects.equals(ipAddress, request.ipAddress())
                && java.util.Objects.equals(buyerEmail, request.buyerEmail())
                && requestedAmount == request.amount()
                && paymentRequestedAt.equals(request.paymentRequestedAt().truncatedTo(ChronoUnit.MICROS));
    }

    public void recordAuthorizationResult(String result) {
        if (!"APPROVED".equals(result) && !"DECLINED".equals(result)) {
            throw new IllegalArgumentException("authorization result must be APPROVED or DECLINED");
        }
        if (authorizationResult != null && !authorizationResult.equals(result)) {
            throw new IllegalStateException("승인 결과는 변경할 수 없습니다");
        }
        authorizationResult = result;
    }

    public void recordReevaluation(UUID originalEvaluationRequestId, UUID retryId) {
        if (this.originalEvaluationRequestId == null && this.reevaluationRetryId == null) {
            this.originalEvaluationRequestId = originalEvaluationRequestId;
            this.reevaluationRetryId = retryId;
            return;
        }
        if (!this.originalEvaluationRequestId.equals(originalEvaluationRequestId)
                || !this.reevaluationRetryId.equals(retryId)) {
            throw new IllegalStateException("재탐지 이력은 변경할 수 없습니다");
        }
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
