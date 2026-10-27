package com.donworry.investigation.report.repository;

import com.donworry.investigation.report.entity.AiReport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 보고서는 버전별로 쌓으므로 물리 삭제를 노출하지 않고 필요한 저장 · 조회만 제공한다
public interface AiReportRepository extends Repository<AiReport, Long> {

    AiReport save(AiReport report);

    // UNIQUE(case_id, report_version) 충돌을 저장 시점에 바로 알기 위해 쓴다
    AiReport saveAndFlush(AiReport report);

    Optional<AiReport> findById(Long reportId);

    List<AiReport> findByCaseIdOrderByReportVersionAsc(Long caseId);

    // 요청한 증거 버전 이상을 이미 분석한 보고서가 있으면 새로 만들지 않는다 (ADR-0009 결정 3)
    boolean existsByCaseIdAndEvidenceVersionGreaterThanEqual(Long caseId, int evidenceVersion);

    // 다음 report_version 계산용 — 해당 Case에 보고서가 없으면 빈 값
    @Query("SELECT MAX(r.reportVersion) FROM AiReport r WHERE r.caseId = :caseId")
    Optional<Integer> findMaxReportVersion(@Param("caseId") Long caseId);
}
