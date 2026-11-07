package com.donworry.identity.entity;

import com.donworry.identity.api.StaffLockOutcome;
import com.donworry.identity.api.StaffLockReason;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 보안 직원 계정 보안 정지 기록 (#647) — 정지할 때 한 줄, 판정으로 풀 때 같은 줄에 해제를 적는다. 원문 칸 없음
// 사람당 열린 정지 1개는 DB 생성 칼럼 open_key UNIQUE가 막는다(매핑하지 않음)
@Entity
@Table(name = "staff_account_locks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StaffAccountLock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lock_id")
    private Long lockId;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40)
    private StaffLockReason reasonCode;

    // P2 내부자 경보 번호 (논리 참조)
    @Column(name = "source_alert_id")
    private Long sourceAlertId;

    @Enumerated(EnumType.STRING)
    @Column(name = "locked_by_type", nullable = false, length = 20)
    private AdminChangeActorType lockedByType;

    @Column(name = "locked_by")
    private Long lockedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StaffAccountLockStatus status;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "locked_at", nullable = false)
    private LocalDateTime lockedAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "released_by")
    private Long releasedBy;

    // P3 내부자 사건 번호 (논리 참조)
    @Column(name = "release_case_id")
    private Long releaseCaseId;

    @Column(name = "release_decision", length = 40)
    private String releaseDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_outcome", length = 20)
    private StaffLockOutcome releaseOutcome;

    public static StaffAccountLock bySystem(Long staffId, StaffLockReason reason, Long sourceAlertId, Instant now) {
        return StaffAccountLock.builder()
                .staffId(staffId)
                .reasonCode(reason)
                .sourceAlertId(sourceAlertId)
                .lockedByType(AdminChangeActorType.SYSTEM)
                .lockedAt(utc(now))
                .build();
    }

    public static StaffAccountLock byAdmin(
            Long staffId, Long adminId, StaffLockReason reason, Long sourceAlertId, Instant now) {
        return StaffAccountLock.builder()
                .staffId(staffId)
                .reasonCode(reason)
                .sourceAlertId(sourceAlertId)
                .lockedByType(AdminChangeActorType.STAFF)
                .lockedBy(Objects.requireNonNull(adminId, "adminId"))
                .lockedAt(utc(now))
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private StaffAccountLock(
            Long staffId,
            StaffLockReason reasonCode,
            Long sourceAlertId,
            AdminChangeActorType lockedByType,
            Long lockedBy,
            LocalDateTime lockedAt) {
        this.staffId = Objects.requireNonNull(staffId, "staffId");
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        this.sourceAlertId = sourceAlertId;
        this.lockedByType = lockedByType;
        this.lockedBy = lockedBy;
        this.status = StaffAccountLockStatus.OPEN;
        this.lockedAt = lockedAt;
    }

    public boolean isOpen() {
        return status == StaffAccountLockStatus.OPEN;
    }

    // 판정으로 해제 — 이미 풀린 기록은 다시 풀지 않는다
    public void release(Long adminId, Long caseId, String decision, StaffLockOutcome outcome, Instant now) {
        if (!isOpen()) throw new IllegalStateException("이미 해제된 정지입니다");
        this.status = StaffAccountLockStatus.RELEASED;
        this.releasedBy = Objects.requireNonNull(adminId, "adminId");
        this.releaseOutcome = Objects.requireNonNull(outcome, "outcome");
        this.releaseCaseId = caseId;
        this.releaseDecision = decision;
        this.releasedAt = utc(now);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(
                Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
