package com.donworry.investigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 조사 Case — 상태 전이 판단은 InvestigationRules, 여기서는 결과만 반영한다
@Entity
@Table(name = "security_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecurityCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "case_id")
    private Long id;

    // P1 merchants 논리 참조
    private Long merchantId;

    private String customerRef;

    @Enumerated(EnumType.STRING)
    private CaseType caseType;

    @Enumerated(EnumType.STRING)
    private CaseStatus status;

    // 결제가 병합될 때마다 증가 — 판정 · 보고서가 어느 증거를 봤는지 비교하는 기준
    private int evidenceVersion;

    private Instant createdAt;

    private Instant closedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private SecurityCase(Long merchantId, String customerRef, CaseType caseType, Instant createdAt) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.customerRef = Objects.requireNonNull(customerRef, "customerRef");
        this.caseType = Objects.requireNonNull(caseType, "caseType");
        // DATETIME(6)에 저장되는 값과 같게 마이크로초로 맞춘다 — 병합 기한 판단이 저장 전후로 달라지지 않게
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt").truncatedTo(ChronoUnit.MICROS);
        this.status = CaseStatus.OPEN;
        this.evidenceVersion = 1;
    }

    public static SecurityCase open(Long merchantId, String customerRef, CaseType caseType, Instant now) {
        return SecurityCase.builder()
                .merchantId(merchantId)
                .customerRef(customerRef)
                .caseType(caseType)
                .createdAt(now)
                .build();
    }

    // 최초 결제는 생성 때의 버전 1에 포함되므로, 이후 병합되는 결제에만 호출한다
    // 동시 병합에서 증가가 덮어써지지 않도록 쓰기 잠금으로 읽은 Case에만 호출한다 (SecurityCaseRepository.findMergeCandidates)
    public void increaseEvidenceVersion() {
        requireNotClosed();
        this.evidenceVersion++;
    }

    // CLOSED로는 close()로만 바꾼다 — closed_at을 함께 남기기 위해
    public void changeStatus(CaseStatus next) {
        Objects.requireNonNull(next, "next");
        requireNotClosed();
        if (next == CaseStatus.CLOSED) {
            throw new IllegalArgumentException("Case 종료는 close()로 처리합니다");
        }
        this.status = next;
    }

    public void close(Instant now) {
        requireNotClosed();
        this.status = CaseStatus.CLOSED;
        this.closedAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isClosed() {
        return status == CaseStatus.CLOSED;
    }

    // 종료된 Case는 금융 실행 결과와 상관없이 다시 열지 않는다 (fds 정본 03 §3.2 P3)
    private void requireNotClosed() {
        if (isClosed()) {
            throw new IllegalStateException("종료된 Case는 바꿀 수 없습니다");
        }
    }
}
