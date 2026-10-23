package com.donworry.investigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Case에 속한 결제 — 금액 · 결제 상태는 저장하지 않고 P5에서 조회한다
@Entity
@Table(name = "case_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CasePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "case_payment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id")
    private SecurityCase securityCase;

    // P5 payments 논리 참조 — UNIQUE라 결제 하나는 Case 하나에만 속한다
    private Long paymentId;

    private Instant linkedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private CasePayment(SecurityCase securityCase, Long paymentId, Instant linkedAt) {
        this.securityCase = Objects.requireNonNull(securityCase, "securityCase");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt").truncatedTo(ChronoUnit.MICROS);
    }

    public static CasePayment link(SecurityCase securityCase, Long paymentId, Instant now) {
        return CasePayment.builder()
                .securityCase(securityCase)
                .paymentId(paymentId)
                .linkedAt(now)
                .build();
    }
}
