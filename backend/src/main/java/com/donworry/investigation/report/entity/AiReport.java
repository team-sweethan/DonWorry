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

// Case별 AI 조사 보고서 — 버전별로 쌓고 덮어쓰지 않는다 (ADR-0009 결정 2 · 3)
@Entity
@Table(name = "ai_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;

    // P3 security_cases 논리 참조 — 같은 모듈이지만 테이블 소유가 달라 연관관계를 걸지 않는다
    private Long caseId;

    private int reportVersion;

    // 이 보고서가 분석한 Case 증거 버전
    private int evidenceVersion;

    @Enumerated(EnumType.STRING)
    private ReportStatus status;

    // 보고서 본문 · 증거 출처 · 실패 정보(error). 완료 전에는 null
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> reportBody;

    private Instant createdAt;

    private Instant completedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private AiReport(Long caseId, int reportVersion, int evidenceVersion, Instant createdAt) {
        if (reportVersion < 1 || evidenceVersion < 1) {
            throw new IllegalArgumentException("보고서 버전과 증거 버전은 1 이상이어야 합니다");
        }
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.reportVersion = reportVersion;
        this.evidenceVersion = evidenceVersion;
        // DATETIME(6)에 저장되는 값과 같게 마이크로초로 맞춘다
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt").truncatedTo(ChronoUnit.MICROS);
        this.status = ReportStatus.PENDING;
    }

    // 새 보고서는 PENDING으로만 만든다. 다음 버전 번호는 호출자가 AiReportRepository로 계산한다
    public static AiReport pending(Long caseId, int reportVersion, int evidenceVersion, Instant now) {
        return AiReport.builder()
                .caseId(caseId)
                .reportVersion(reportVersion)
                .evidenceVersion(evidenceVersion)
                .createdAt(now)
                .build();
    }
}
