package com.donworry.investigation.report.entity;

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
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 내부자 사건 해제 검토 보고서 한 판 (#675, ADR-0082 조각 9) — 만들 때 끝난 상태로 저장하고 바꾸지 않는다
// 판은 쌓기만 한다. 같은 사건의 같은 계기는 한 판(uk_ai_insider_reports_trigger)
@Entity
@Table(name = "ai_insider_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsiderReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;

    // P3 insider_cases 논리 참조 — 같은 모듈이지만 테이블 소유가 달라 연관관계를 걸지 않는다
    private Long insiderCaseId;

    private int reportVersion;

    @Enumerated(EnumType.STRING)
    private InsiderReportTrigger triggerType;

    // P2 insider_alerts 논리 참조 — 이 판을 만든 경보
    private Long triggerAlertId;

    // SUCCEEDED · FAILED만 쓴다
    @Enumerated(EnumType.STRING)
    private ReportStatus status;

    // 사실(facts) · 설명(narrative) · 한계(limits). 실패면 오류 코드(error)만
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> reportBody;

    private Instant createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private InsiderReport(
            Long insiderCaseId,
            int reportVersion,
            InsiderReportTrigger triggerType,
            Long triggerAlertId,
            ReportStatus status,
            Map<String, Object> reportBody,
            Instant createdAt) {
        if (reportVersion < 1) {
            throw new IllegalArgumentException("보고서 판 번호는 1 이상이어야 합니다");
        }
        this.insiderCaseId = Objects.requireNonNull(insiderCaseId, "insiderCaseId");
        this.reportVersion = reportVersion;
        this.triggerType = Objects.requireNonNull(triggerType, "triggerType");
        this.triggerAlertId = Objects.requireNonNull(triggerAlertId, "triggerAlertId");
        this.status = Objects.requireNonNull(status, "status");
        this.reportBody = Objects.requireNonNull(reportBody, "reportBody");
        // DATETIME(6)에 저장되는 값과 같게 마이크로초로 맞춘다
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt").truncatedTo(ChronoUnit.MICROS);
    }

    // 끝난 상태(SUCCEEDED · FAILED)로만 만든다. 판 번호는 호출자가 InsiderReportRepository로 계산한다
    public static InsiderReport finished(
            Long insiderCaseId,
            int reportVersion,
            InsiderReportTrigger trigger,
            Long triggerAlertId,
            ReportStatus status,
            Map<String, Object> body,
            Instant now) {
        if (status != ReportStatus.SUCCEEDED && status != ReportStatus.FAILED) {
            throw new IllegalArgumentException("끝난 상태로만 저장합니다");
        }
        return InsiderReport.builder()
                .insiderCaseId(insiderCaseId)
                .reportVersion(reportVersion)
                .triggerType(trigger)
                .triggerAlertId(triggerAlertId)
                .status(status)
                .reportBody(body)
                .createdAt(now)
                .build();
    }
}
