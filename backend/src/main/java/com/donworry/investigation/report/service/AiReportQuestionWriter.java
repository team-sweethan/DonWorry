package com.donworry.investigation.report.service;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.AiQuestionRequest;
import com.donworry.investigation.report.dto.AiQuestionResult;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.AiReportQuestion;
import com.donworry.investigation.report.entity.QuestionStatus;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.AiReportQuestionRepository;
import com.donworry.investigation.report.repository.AiReportRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.CaseAccessGuard;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// 사건 질문의 트랜잭션 1(접수 start) · 트랜잭션 2(결과 저장 answer · fail) (ADR-0086 6절)
// AiReportQuestionService 안에서 직접 부르면 @Transactional이 걸리지 않아 별도 빈으로 뺐다. ai-server 호출은 둘 사이, 트랜잭션 밖에서 한다
@Component
@RequiredArgsConstructor
class AiReportQuestionWriter {

    static final int DAILY_LIMIT = 30;
    static final Duration LIMIT_WINDOW = Duration.ofHours(24);

    private static final List<QuestionStatus> FINISHED_ANSWERS =
            List.of(QuestionStatus.ANSWERED, QuestionStatus.DECLINED);

    private final SecurityCaseRepository securityCaseRepository;
    private final AiReportRepository aiReportRepository;
    private final AiReportQuestionRepository questionRepository;
    private final CaseAccessGuard caseAccessGuard;
    private final StaffAccessRecorder accessRecorder;
    private final AiSwitchService aiSwitchService;

    // 권한 · 대상 · 긴급정지 · 횟수 상한을 확인하고 질문 줄(RUNNING)과 접근 기록을 함께 커밋한다
    // 하나라도 막히면 줄도 허용 기록도 남지 않는다 (담당 아님 거부는 CaseAccessGuard가 따로 기록)
    @Transactional
    public AiQuestionRequest start(Long caseId, Long reportId, Long staffId, String question) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        caseAccessGuard.requireAccess(securityCase, staffId, StaffAccessType.REPORT_QUESTION);
        AiReport report = reportOf(caseId, reportId);
        if (report.getStatus() != ReportStatus.SUCCEEDED || isFixedReport(report)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI가 만든 보고서에만 질문할 수 있습니다.");
        }
        if (!aiSwitchService.isAiAllowed()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "지금은 AI 질문을 받을 수 없습니다.");
        }
        Instant now = Instant.now();
        long recent =
                questionRepository.countByReportIdAndStaffIdAndAskedAtAfter(reportId, staffId, now.minus(LIMIT_WINDOW));
        if (recent >= DAILY_LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "오늘 질문 한도를 넘었습니다.");
        }
        List<AiQuestionRequest.PreviousTurn> previous = previousTurns(reportId, staffId);
        AiReportQuestion saved = questionRepository.save(AiReportQuestion.asked(report, staffId, question, now));
        accessRecorder.recordAllowed(StaffAccess.builder()
                .accessType(StaffAccessType.REPORT_QUESTION)
                .caseId(caseId)
                .merchantId(securityCase.getMerchantId())
                .build());
        return AiQuestionRequest.builder()
                .questionId(String.valueOf(saved.getId()))
                .reportId(String.valueOf(reportId))
                .caseId(String.valueOf(caseId))
                .reportEvidenceVersion(report.getEvidenceVersion())
                .currentEvidenceVersion(securityCase.getEvidenceVersion())
                .question(question)
                .reportBody(report.getReportBody())
                .previous(previous)
                .build();
    }

    // 검사를 통과한 답을 저장하고 끝낸다. 호출 쪽이 AI 스위치 잠금(whenAiAllowed) 안에서 불러
    // 스위치 확인부터 이 트랜잭션 커밋까지 정지가 끼지 않게 한다 (ADR-0083 결정 5, #764 Codex 리뷰)
    @Transactional
    public AiReportQuestion answer(Long questionId, AiQuestionResult result) {
        AiReportQuestion question = load(questionId);
        QuestionStatus status = "DECLINED".equals(result.status()) ? QuestionStatus.DECLINED : QuestionStatus.ANSWERED;
        question.answer(status, answerBody(result), Instant.now());
        return question;
    }

    // 실패 정보(code · reason)를 남기고 끝낸다
    @Transactional
    public AiReportQuestion fail(Long questionId, String code, String reason) {
        AiReportQuestion question = load(questionId);
        question.fail(Map.of("code", code, "reason", reason), Instant.now());
        return question;
    }

    private AiReportQuestion load(Long questionId) {
        return questionRepository
                .findById(questionId)
                .orElseThrow(() -> new IllegalStateException("질문이 없습니다: " + questionId));
    }

    // 목록 조회도 같은 권한 · 같은 사건 확인을 거친다
    @Transactional(readOnly = true)
    public List<AiReportQuestion> listOwn(Long caseId, Long reportId, Long staffId) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        caseAccessGuard.requireAccess(securityCase, staffId, StaffAccessType.REPORT_QUESTION);
        reportOf(caseId, reportId);
        return questionRepository.findByReportIdAndStaffIdOrderByIdAsc(reportId, staffId);
    }

    // 다른 사건의 보고서 번호는 없는 것과 같다 — 권한을 확인한 사건 밖으로 나가지 않는다
    private AiReport reportOf(Long caseId, Long reportId) {
        return aiReportRepository
                .findById(reportId)
                .filter(report -> report.getCaseId().equals(caseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이 Case의 AI 보고서가 아닙니다."));
    }

    // AI 정지 · 장애 때 Spring이 만든 고정 보고서 (FixedReportGenerator.fixedTrace) — AI가 조사하지 않아 대상이 아니다 (ADR-0086 2절)
    private static boolean isFixedReport(AiReport report) {
        Map<String, Object> body = report.getReportBody();
        return body != null
                && body.get("investigationTrace") instanceof Map<?, ?> trace
                && "FIXED".equals(trace.get("provider"));
    }

    // 같은 보고서 · 같은 직원의 끝난 답(실패 제외) 최근 3개를 물은 순서대로 (ADR-0086 1절)
    private List<AiQuestionRequest.PreviousTurn> previousTurns(Long reportId, Long staffId) {
        List<AiReportQuestion> recent =
                new ArrayList<>(questionRepository.findTop3ByReportIdAndStaffIdAndStatusInOrderByIdDesc(
                        reportId, staffId, FINISHED_ANSWERS));
        return recent.reversed().stream()
                .map(q -> AiQuestionRequest.PreviousTurn.builder()
                        .question(q.getQuestion())
                        .kind((String) q.getAnswerBody().get("kind"))
                        .sentences(listOf(q.getAnswerBody().get("sentences")))
                        .facts(listOf(q.getAnswerBody().get("facts")))
                        .build())
                .toList();
    }

    private static Map<String, Object> answerBody(AiQuestionResult result) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", result.kind());
        body.put("sentences", nullToEmpty(result.sentences()));
        body.put("checks", nullToEmpty(result.checks()));
        body.put("facts", nullToEmpty(result.facts()));
        body.put("toolCalls", nullToEmpty(result.toolCalls()));
        body.put("trace", nullToEmpty(result.trace()));
        return body;
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
