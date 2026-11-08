package com.donworry.investigation.entity;

import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 내부자 사건에 묶인 경보 한 줄 (#670). 경보 번호가 PK라 같은 경보가 다시 와도 한 줄이다
// 규칙 · 등급 · 요약은 화면용 사본 — P2 요약에는 건수 · 규칙만 있고 원문 · IP는 없다
@Entity
@Table(name = "insider_case_alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsiderCaseAlert {

    // P2 insider_alerts 논리 참조. 밖에서 정하는 번호라 자동 생성이 아니다
    @Id
    private Long alertId;

    private Long insiderCaseId;

    @Enumerated(EnumType.STRING)
    private InsiderRule ruleCode;

    @Enumerated(EnumType.STRING)
    private InsiderAlertSeverity severity;

    private String summary;

    private Instant raisedAt;

    private Instant linkedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private InsiderCaseAlert(
            Long alertId,
            Long insiderCaseId,
            InsiderRule ruleCode,
            InsiderAlertSeverity severity,
            String summary,
            Instant raisedAt,
            Instant linkedAt) {
        this.alertId = Objects.requireNonNull(alertId, "alertId");
        this.insiderCaseId = Objects.requireNonNull(insiderCaseId, "insiderCaseId");
        this.ruleCode = Objects.requireNonNull(ruleCode, "ruleCode");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.summary = Objects.requireNonNull(summary, "summary");
        this.raisedAt = micros(raisedAt);
        this.linkedAt = micros(linkedAt);
    }

    public static InsiderCaseAlert link(
            Long insiderCaseId,
            Long alertId,
            InsiderRule ruleCode,
            InsiderAlertSeverity severity,
            String summary,
            Instant raisedAt,
            Instant now) {
        return InsiderCaseAlert.builder()
                .insiderCaseId(insiderCaseId)
                .alertId(alertId)
                .ruleCode(ruleCode)
                .severity(severity)
                .summary(summary)
                .raisedAt(raisedAt)
                .linkedAt(now)
                .build();
    }

    // 같은 경보가 더 높은 등급으로 다시 왔을 때. 낮은 등급 · 늦게 온 옛 알림으로 덮지 않는다 — 올랐으면 true
    public boolean raiseTo(InsiderAlertSeverity found, String newSummary, Instant newRaisedAt) {
        if (!found.isHigherThan(severity)) return false;
        this.severity = found;
        this.summary = Objects.requireNonNull(newSummary, "summary");
        this.raisedAt = micros(newRaisedAt);
        return true;
    }

    private static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "time").truncatedTo(ChronoUnit.MICROS);
    }
}
