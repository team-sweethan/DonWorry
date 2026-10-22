package com.donworry.investigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 결제별 직원 최종 판정 — 한 번 저장하면 바꾸지 않는다
@Entity
@Table(name = "payment_decisions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "decision_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_payment_id")
    private CasePayment casePayment;

    // P1 platform_users 논리 참조
    private Long staffId;

    // P4 ai_reports 논리 참조 — AI 보고서 3회 실패 후 수동 판정이면 null
    private Long reportId;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    @Column(columnDefinition = "TEXT")
    private String reason;

    // 판정 때 직원이 확인한 Case 증거 버전 — 수동 판정에도 반드시 남긴다
    private int evidenceVersion;

    private Instant decidedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentDecision(
            CasePayment casePayment,
            Long staffId,
            Long reportId,
            Decision decision,
            String reason,
            int evidenceVersion,
            Instant decidedAt) {
        this.casePayment = Objects.requireNonNull(casePayment, "casePayment");
        this.staffId = Objects.requireNonNull(staffId, "staffId");
        this.reportId = reportId;
        this.decision = Objects.requireNonNull(decision, "decision");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.evidenceVersion = evidenceVersion;
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    // 증거 버전 일치 여부는 저장 전에 Service가 확인한다 (fds 정본 03 §3.5)
    public static PaymentDecision confirm(
            CasePayment casePayment,
            Long staffId,
            Long reportId,
            Decision decision,
            String reason,
            int evidenceVersion,
            Instant now) {
        return PaymentDecision.builder()
                .casePayment(casePayment)
                .staffId(staffId)
                .reportId(reportId)
                .decision(decision)
                .reason(reason)
                .evidenceVersion(evidenceVersion)
                .decidedAt(now)
                .build();
    }

    public boolean isManual() {
        return reportId == null;
    }
}
