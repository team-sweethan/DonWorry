package com.donworry.investigation.report.repository;

import com.donworry.investigation.report.entity.AiReport;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 보고서는 버전별로 쌓으므로 물리 삭제를 노출하지 않고 필요한 저장 · 조회만 제공한다
public interface AiReportRepository extends Repository<AiReport, Long> {

    AiReport save(AiReport report);

    Optional<AiReport> findById(Long reportId);

    // 다음 report_version 계산용 — 해당 Case에 보고서가 없으면 빈 값
    @Query("SELECT MAX(r.reportVersion) FROM AiReport r WHERE r.caseId = :caseId")
    Optional<Integer> findMaxReportVersion(@Param("caseId") Long caseId);
}
