package com.donworry.investigation.report.repository;

import com.donworry.investigation.report.entity.InsiderReport;
import com.donworry.investigation.report.entity.InsiderReportTrigger;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 내부자 해제 검토 보고서 (#675) — 판별로 쌓고 지우거나 고치지 않는다
public interface InsiderReportRepository extends Repository<InsiderReport, Long> {

    // UNIQUE(사건, 판) · UNIQUE(사건, 계기) 충돌을 저장 시점에 바로 알기 위해 쓴다
    InsiderReport saveAndFlush(InsiderReport report);

    boolean existsByInsiderCaseIdAndTriggerType(Long insiderCaseId, InsiderReportTrigger triggerType);

    // 다음 판 번호 계산용 — 보고서가 없으면 빈 값
    @Query("SELECT MAX(r.reportVersion) FROM InsiderReport r WHERE r.insiderCaseId = :insiderCaseId")
    Optional<Integer> findMaxReportVersion(@Param("insiderCaseId") Long insiderCaseId);

    List<InsiderReport> findByInsiderCaseIdOrderByReportVersionAsc(Long insiderCaseId);
}
