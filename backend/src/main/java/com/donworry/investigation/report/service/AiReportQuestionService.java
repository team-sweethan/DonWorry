package com.donworry.investigation.report.service;

import com.donworry.investigation.report.client.AiQuestionOutcome;
import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.dto.AiQuestionRequest;
import com.donworry.investigation.report.dto.ReportQuestionResponse;
import com.donworry.investigation.report.entity.AiReportQuestion;
import com.donworry.investigation.report.entity.QuestionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// 보고서 뒤 사건 질문 · 답변 (ADR-0086) — 결제 사건의 성공한 AI 보고서에 직원이 묻고, ai-server가 보고서 사실로 답한다
// 답은 판정 근거가 아니다. 실패해도 사건 · 판정 상태를 바꾸지 않는다 (AGENTS.md 6절)
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportQuestionService {

    static final int QUESTION_MAX_LENGTH = 300;

    // 서버가 ai-server 호출 중에 꺼지면 RUNNING으로 남는다 — 이보다 오래된 RUNNING은 실패로 보인다 (DB는 바꾸지 않음)
    static final Duration STALE_RUNNING = Duration.ofMinutes(2);

    private final AiReportQuestionWriter writer;
    private final AiServerClient aiServerClient;
    private final AiSwitchService aiSwitchService;

    // 트랜잭션을 걸지 않는다 — 접수(트랜잭션 1) → ai-server 호출(트랜잭션 밖, 30초) → 결과 저장(트랜잭션 2) (ADR-0086 6절)
    // 자동 재시도는 없다. 실패면 직원이 다시 묻는다
    public ReportQuestionResponse ask(Long caseId, Long reportId, Long staffId, String question) {
        String trimmed = question == null ? "" : question.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > QUESTION_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문을 1~300자로 입력해 주세요.");
        }
        AiQuestionRequest request = writer.start(caseId, reportId, staffId, trimmed);
        AiQuestionOutcome outcome;
        try {
            outcome = aiServerClient.ask(request);
        } catch (RuntimeException e) {
            log.warn("사건 질문 호출 실패 questionId={}", request.questionId(), e);
            outcome = new AiQuestionOutcome.Failed("CLIENT_ERROR", "ai-server 호출 중 예외");
        }
        if (outcome instanceof AiQuestionOutcome.Failed failed) {
            log.warn("사건 질문 답 실패 questionId={} code={}", request.questionId(), failed.code());
        }
        return toResponse(finish(Long.valueOf(request.questionId()), outcome), Instant.now());
    }

    // 답 저장은 AI 스위치 잠금 안에서 트랜잭션 커밋까지 끝낸다 — 정지가 먼저 저장됐으면 답을 버리고 실패로 남긴다
    // (ADR-0083 결정 5, AiInvestigationRunner.finishSucceeded와 같은 모양, #764 Codex 리뷰)
    private AiReportQuestion finish(Long questionId, AiQuestionOutcome outcome) {
        return switch (outcome) {
            case AiQuestionOutcome.Answered answered ->
                aiSwitchService
                        .whenAiAllowed(() -> writer.answer(questionId, answered.result()))
                        .orElseGet(() -> {
                            log.info("AI 조사 정지 중 — 질문 답을 저장하지 않음 questionId={}", questionId);
                            return writer.fail(questionId, "AI_STOPPED", "답을 받는 동안 AI 조사가 정지됨");
                        });
            case AiQuestionOutcome.Failed failed -> writer.fail(questionId, failed.code(), failed.reason());
        };
    }

    // 같은 보고서에 내가 한 질문만, 물은 순서대로 (ADR-0086 7절)
    public List<ReportQuestionResponse> list(Long caseId, Long reportId, Long staffId) {
        Instant now = Instant.now();
        return writer.listOwn(caseId, reportId, staffId).stream()
                .map(question -> toResponse(question, now))
                .toList();
    }

    private static ReportQuestionResponse toResponse(AiReportQuestion question, Instant now) {
        QuestionStatus status = question.getStatus();
        boolean stale = status == QuestionStatus.RUNNING
                && question.getAskedAt().plus(STALE_RUNNING).isBefore(now);
        boolean answered = status == QuestionStatus.ANSWERED || status == QuestionStatus.DECLINED;
        Map<String, Object> body = answered ? question.getAnswerBody() : Map.of();
        return ReportQuestionResponse.builder()
                .questionId(question.getId())
                .question(question.getQuestion())
                .status(stale ? QuestionStatus.FAILED.name() : status.name())
                .kind(answered ? (String) body.get("kind") : null)
                .sentences(listOf(body.get("sentences")))
                .checks(listOf(body.get("checks")))
                .facts(listOf(body.get("facts")))
                .askedAt(question.getAskedAt())
                .answeredAt(question.getAnsweredAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
