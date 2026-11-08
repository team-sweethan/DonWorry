package com.donworry.investigation.entity;

import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.identity.api.StaffLockOutcome;
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

// 내부자 사건 (#670, ADR-0082 조각 8) — 높음 이상 내부자 경보를 직원 한 명당 열린 사건 하나로 묶는다. 원문 칸은 없다
// 판정 3개 중 하나로 닫고, 징계로 닫힌 사건만 나중에 사유를 적고 정지를 풀 수 있다
@Entity
@Table(name = "insider_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsiderCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "insider_case_id")
    private Long id;

    // P1 platform_users 논리 참조
    private Long staffId;

    @Enumerated(EnumType.STRING)
    private InsiderCaseStatus status;

    // 묶인 경보 중 가장 높은 등급 — 올라가기만 한다
    @Enumerated(EnumType.STRING)
    private InsiderAlertSeverity severity;

    private Instant openedAt;

    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    private InsiderCaseDecision decision;

    private String decisionMemo;

    private Long decidedByAdminId;

    // 처리 시점의 ADMIN 표시 이름 — SecurityStaffDirectory에 ADMIN이 없어 함께 남긴다
    private String decidedByAdminName;

    private Instant decidedAt;

    private Instant lockReleasedAt;

    private Long lockReleasedByAdminId;

    private String lockReleasedByAdminName;

    private String lockReleaseReason;

    @Enumerated(EnumType.STRING)
    private StaffLockOutcome lockReleaseOutcome;

    @Builder(access = AccessLevel.PRIVATE)
    private InsiderCase(Long staffId, InsiderAlertSeverity severity, Instant openedAt) {
        this.staffId = Objects.requireNonNull(staffId, "staffId");
        this.severity = requireCaseSeverity(severity);
        this.openedAt = micros(openedAt);
        this.updatedAt = this.openedAt;
        this.status = InsiderCaseStatus.OPEN;
    }

    public static InsiderCase open(Long staffId, InsiderAlertSeverity severity, Instant now) {
        return InsiderCase.builder()
                .staffId(staffId)
                .severity(severity)
                .openedAt(now)
                .build();
    }

    // 새 경보가 붙거나 등급이 올랐을 때. 낮은 등급으로 덮지 않는다
    public void touch(InsiderAlertSeverity found, Instant now) {
        if (status != InsiderCaseStatus.OPEN) throw new IllegalStateException("닫힌 내부자 사건입니다");
        if (requireCaseSeverity(found).isHigherThan(severity)) this.severity = found;
        this.updatedAt = micros(now);
    }

    public void decide(InsiderCaseDecision result, Long adminId, String adminName, String memo, Instant now) {
        if (status != InsiderCaseStatus.OPEN) throw new IllegalStateException("이미 판정한 내부자 사건입니다");
        this.decision = Objects.requireNonNull(result, "decision");
        this.decidedByAdminId = Objects.requireNonNull(adminId, "adminId");
        this.decidedByAdminName = adminName;
        this.decisionMemo = memo;
        this.decidedAt = micros(now);
        this.updatedAt = this.decidedAt;
        this.status = InsiderCaseStatus.CLOSED;
    }

    // 징계로 닫힌 사건의 정지 해제 기록. 한 번만 — 첫 해제의 누가 · 왜를 덮어쓰지 않는다
    public void recordLockRelease(
            Long adminId, String adminName, String reason, StaffLockOutcome outcome, Instant now) {
        if (!isDisciplined()) throw new IllegalStateException("징계 요청으로 닫힌 사건이 아닙니다");
        if (isLockReleased()) throw new IllegalStateException("이미 정지를 푼 사건입니다");
        this.lockReleasedByAdminId = Objects.requireNonNull(adminId, "adminId");
        this.lockReleasedByAdminName = adminName;
        this.lockReleaseReason = Objects.requireNonNull(reason, "reason");
        this.lockReleaseOutcome = Objects.requireNonNull(outcome, "outcome");
        this.lockReleasedAt = micros(now);
        this.updatedAt = this.lockReleasedAt;
    }

    public boolean isOpen() {
        return status == InsiderCaseStatus.OPEN;
    }

    public boolean isDisciplined() {
        return status == InsiderCaseStatus.CLOSED && decision == InsiderCaseDecision.DISCIPLINE_REQUEST;
    }

    public boolean isLockReleased() {
        return lockReleasedAt != null;
    }

    // 주의 등급 경보는 사건을 열지 않는다 (ADR-0082) — DB CHECK와 같은 조건
    private static InsiderAlertSeverity requireCaseSeverity(InsiderAlertSeverity severity) {
        if (Objects.requireNonNull(severity, "severity") == InsiderAlertSeverity.CAUTION) {
            throw new IllegalArgumentException("주의 등급 경보는 내부자 사건을 열지 않습니다");
        }
        return severity;
    }

    // DATETIME(6)에 맞춰 자른다
    private static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "time").truncatedTo(ChronoUnit.MICROS);
    }
}
