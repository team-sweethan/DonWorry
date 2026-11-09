package com.donworry.investigation.report.repository;

import com.donworry.investigation.report.entity.AiReportQuestion;
import com.donworry.investigation.report.entity.QuestionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// 질문은 쌓기만 하므로 물리 삭제를 노출하지 않는다 (ADR-0086 5절)
public interface AiReportQuestionRepository extends Repository<AiReportQuestion, Long> {

    AiReportQuestion save(AiReportQuestion question);

    // 답 저장을 AI 스위치 잠금 안에서 DB에 바로 반영한다 (AiSwitchService.whenAiAllowed)
    AiReportQuestion saveAndFlush(AiReportQuestion question);

    Optional<AiReportQuestion> findById(Long questionId);

    // 질문 횟수 상한 — 실패한 질문도 센다 (AI를 이미 불렀을 수 있다)
    long countByReportIdAndStaffIdAndAskedAtAfter(Long reportId, Long staffId, Instant since);

    // 이어서 묻기 — 같은 보고서 · 같은 직원의 끝난 답 중 최근 것부터
    List<AiReportQuestion> findTop3ByReportIdAndStaffIdAndStatusInOrderByIdDesc(
            Long reportId, Long staffId, Collection<QuestionStatus> statuses);

    // 화면 목록 — 같은 보고서의 내 질문, 물은 순서대로
    List<AiReportQuestion> findByReportIdAndStaffIdOrderByIdAsc(Long reportId, Long staffId);
}
