package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.repository.AiReportRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// ai_reports 저장 전용 — 보고서 요청은 P3 커밋 뒤 리스너에서 오므로 저장마다 새 트랜잭션으로 커밋한다 (02-code 4절)
// AiReportService 안에서 직접 부르면 @Transactional이 걸리지 않아 별도 빈으로 뺐다
@Component
@RequiredArgsConstructor
class AiReportWriter {

    private final AiReportRepository aiReportRepository;
    private final SecurityCaseRepository securityCaseRepository;

    // 만들 필요가 없으면 빈 값 — 낡은 요청(증거 버전이 이미 앞섬)이거나 같은 버전 이상을 이미 조사한 경우
    // 동시 요청이 같은 report_version을 잡으면 DataIntegrityViolationException을 던진다 — 호출자가 다시 시도한다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AiReport> createPending(Long caseId, int evidenceVersion, Instant now) {
        boolean current = securityCaseRepository
                .findById(caseId)
                .map(SecurityCase::getEvidenceVersion)
                .filter(version -> version == evidenceVersion)
                .isPresent();
        if (!current || aiReportRepository.existsByCaseIdAndEvidenceVersionGreaterThanEqual(caseId, evidenceVersion)) {
            return Optional.empty();
        }
        int nextVersion = aiReportRepository.findMaxReportVersion(caseId).orElse(0) + 1;
        return Optional.of(
                aiReportRepository.saveAndFlush(AiReport.pending(caseId, nextVersion, evidenceVersion, now)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long reportId, Map<String, Object> body, Instant now) {
        aiReportRepository.findById(reportId).ifPresent(report -> report.succeed(body, now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long reportId, Map<String, Object> error, Instant now) {
        aiReportRepository.findById(reportId).ifPresent(report -> report.fail(error, now));
    }
}
