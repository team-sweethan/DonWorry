package com.donworry.investigation.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// P3 → P4 보고서 요청 · 조회 경계. ai_reports는 P4 소유라 P3는 이 경계로만 읽는다
// 구현은 P4 report.service.AiReportService — 보고서를 ai_reports에 저장한다 (ADR-0009)
public interface ReportGateway {

    // 커밋 뒤에만 부른다 — 실제 구현은 FastAPI를 호출한다 (02-code 4절)
    void request(Long caseId, int evidenceVersion);

    List<ReportSnapshot> findByCase(Long caseId);

    default Optional<ReportSnapshot> latest(Long caseId) {
        return findByCase(caseId).stream().max(Comparator.comparingInt(ReportSnapshot::reportVersion));
    }

    // 상태값은 P4 정본(PENDING · RUNNING · SUCCEEDED · FAILED)을 문자열로 받는다
    record ReportSnapshot(
            Long reportId,
            Long caseId,
            int reportVersion,
            int evidenceVersion,
            String status,
            Map<String, Object> reportBody,
            Instant createdAt,
            Instant completedAt) {

        public boolean isSucceeded() {
            return "SUCCEEDED".equals(status);
        }

        // 총 3회 시도 후 최종 실패 — 직원 수동 판정을 허용한다 (fds 정본 02 §2.5)
        public boolean isFailed() {
            return "FAILED".equals(status);
        }
    }
}
