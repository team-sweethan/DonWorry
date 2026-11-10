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

// 보고서 뒤 사건 질문 하나 (ADR-0086 5절) — 쌓기만 하고 덮어쓰거나 지우지 않는다
// 접수 때 RUNNING으로 저장하고, ai-server 답을 받은 뒤 한 번만 끝낸다
@Entity
@Table(name = "ai_report_questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiReportQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long id;

    // P4 ai_reports (물리 FK, 같은 담당)
    private Long reportId;

    // P3 security_cases 논리 참조
    private Long caseId;

    // P1 직원 논리 참조
    private Long staffId;

    private String question;

    @Enumerated(EnumType.STRING)
    private QuestionStatus status;

    // 답 종류 · 문장 · 근거 사실 · 부른 도구 · 과정 기록, 실패면 error(code · reason). 끝나기 전에는 null
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> answerBody;

    // 질문한 보고서가 분석한 증거 버전
    private int reportEvidenceVersion;

    private Instant askedAt;

    private Instant answeredAt;

    @Builder(access = AccessLevel.PRIVATE)
    private AiReportQuestion(
            Long reportId, Long caseId, Long staffId, String question, int reportEvidenceVersion, Instant askedAt) {
        this.reportId = Objects.requireNonNull(reportId, "reportId");
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.staffId = Objects.requireNonNull(staffId, "staffId");
        this.question = Objects.requireNonNull(question, "question");
        this.reportEvidenceVersion = reportEvidenceVersion;
        // DATETIME(6)에 저장되는 값과 같게 마이크로초로 맞춘다
        this.askedAt = Objects.requireNonNull(askedAt, "askedAt").truncatedTo(ChronoUnit.MICROS);
        this.status = QuestionStatus.RUNNING;
    }

    // 새 질문은 RUNNING으로만 만든다
    public static AiReportQuestion asked(AiReport report, Long staffId, String question, Instant now) {
        return AiReportQuestion.builder()
                .reportId(report.getId())
                .caseId(report.getCaseId())
                .staffId(staffId)
                .question(question)
                .reportEvidenceVersion(report.getEvidenceVersion())
                .askedAt(now)
                .build();
    }

    public boolean isFinished() {
        return status != QuestionStatus.RUNNING;
    }

    // 답(ANSWERED · DECLINED)을 저장하고 끝낸다. 이미 끝났으면 바꾸지 않고 false
    public boolean answer(QuestionStatus result, Map<String, Object> body, Instant now) {
        if (result != QuestionStatus.ANSWERED && result != QuestionStatus.DECLINED) {
            throw new IllegalArgumentException("답은 ANSWERED · DECLINED로만 저장합니다");
        }
        return finish(result, Objects.requireNonNull(body, "body"), now);
    }

    // 실패 정보는 answer_body.error에 남긴다. 이미 끝났으면 바꾸지 않고 false
    public boolean fail(Map<String, Object> error, Instant now) {
        return finish(QuestionStatus.FAILED, Map.of("error", Objects.requireNonNull(error, "error")), now);
    }

    private boolean finish(QuestionStatus result, Map<String, Object> body, Instant now) {
        if (isFinished()) {
            return false;
        }
        this.status = result;
        this.answerBody = body;
        this.answeredAt = now.truncatedTo(ChronoUnit.MICROS);
        return true;
    }
}
