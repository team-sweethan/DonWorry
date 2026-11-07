package com.donworry.fds.entity;

import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
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

// 내부자 경보 (#516) — 사람 · 규칙당 열린 경보 1개(open_key UNIQUE, DB 생성 칼럼이라 매핑하지 않음). 원문 · IP 칸 없음
@Entity
@Table(name = "insider_alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsiderAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Long id;

    // P1 platform_users 논리 참조
    private Long staffId;

    @Enumerated(EnumType.STRING)
    private InsiderRule ruleCode;

    @Enumerated(EnumType.STRING)
    private InsiderAlertSeverity severity;

    @Enumerated(EnumType.STRING)
    private InsiderAlertStatus status;

    private String summary;

    private Instant firstEventAt;

    private Instant lastEventAt;

    private Instant createdAt;

    private Instant updatedAt;

    private Long closedByAdminId;

    private String closedByAdminName;

    private Instant closedAt;

    private String closeMemo;

    @Builder(access = AccessLevel.PRIVATE)
    private InsiderAlert(
            Long staffId,
            InsiderRule ruleCode,
            InsiderAlertSeverity severity,
            String summary,
            Instant firstEventAt,
            Instant lastEventAt,
            Instant now) {
        this.staffId = Objects.requireNonNull(staffId, "staffId");
        this.ruleCode = Objects.requireNonNull(ruleCode, "ruleCode");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.summary = Objects.requireNonNull(summary, "summary");
        this.firstEventAt = micros(firstEventAt);
        this.lastEventAt = micros(lastEventAt);
        this.createdAt = micros(now);
        this.updatedAt = this.createdAt;
        this.status = InsiderAlertStatus.OPEN;
    }

    public static InsiderAlert open(
            Long staffId,
            InsiderRule rule,
            InsiderAlertSeverity severity,
            String summary,
            Instant firstEventAt,
            Instant lastEventAt,
            Instant now) {
        return InsiderAlert.builder()
                .staffId(staffId)
                .ruleCode(rule)
                .severity(severity)
                .summary(summary)
                .firstEventAt(firstEventAt)
                .lastEventAt(lastEventAt)
                .now(now)
                .build();
    }

    // 열린 경보에 새 집계를 반영한다. 등급은 올라가기만 한다(낮은 집계로 덮지 않음) — 올랐으면 true
    public boolean refresh(InsiderAlertSeverity found, String newSummary, Instant newLastEventAt, Instant now) {
        if (status != InsiderAlertStatus.OPEN) throw new IllegalStateException("닫힌 경보입니다");
        boolean raised = found.isHigherThan(severity);
        if (!severity.isHigherThan(found)) {
            this.severity = found;
            this.summary = Objects.requireNonNull(newSummary, "summary");
        }
        if (newLastEventAt.isAfter(lastEventAt)) this.lastEventAt = micros(newLastEventAt);
        this.updatedAt = micros(now);
        return raised;
    }

    public void close(Long adminId, String adminName, String memo, Instant now) {
        if (status != InsiderAlertStatus.OPEN) throw new IllegalStateException("이미 닫힌 경보입니다");
        this.status = InsiderAlertStatus.CLOSED;
        this.closedByAdminId = Objects.requireNonNull(adminId, "adminId");
        this.closedByAdminName = adminName;
        this.closeMemo = memo;
        this.closedAt = micros(now);
        this.updatedAt = this.closedAt;
    }

    public boolean isOpen() {
        return status == InsiderAlertStatus.OPEN;
    }

    // DATETIME(6)에 맞춰 자른다 — 저장 전후 값이 달라 "닫힌 시각 이후" 비교가 어긋나지 않게
    private static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "time").truncatedTo(ChronoUnit.MICROS);
    }
}
