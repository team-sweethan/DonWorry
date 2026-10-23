package com.donworry.investigation.service.mock;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.ReportGateway;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 목업 — P4 보고서 생성을 흉내 내 요청 즉시 SUCCEEDED 보고서를 메모리에 쌓는다 (Notion "목업 데이터 · 연동 현황" P3 표)
// report_body 키는 화면 ReportBody(fds 정본 02 §2.7)를 따른다. P4와 확정 전이다
// ai_reports는 P4 소유라 쓰지 않는다. P4 보고서 서비스가 생기면 이 클래스를 지운다
@Component
@RequiredArgsConstructor
public class MockReportGateway implements ReportGateway {

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentInfoReader paymentInfoReader;

    private final AtomicLong reportIds = new AtomicLong();
    private final Map<Long, List<ReportSnapshot>> reports = new ConcurrentHashMap<>();

    // 같은 증거 버전을 이미 조사했으면 새 버전을 만들지 않는다 — 요청이 두 번 와도 보고서는 하나
    @Override
    public void request(Long caseId, int evidenceVersion) {
        List<ReportSnapshot> caseReports = reports.computeIfAbsent(caseId, id -> new CopyOnWriteArrayList<>());
        synchronized (caseReports) {
            if (caseReports.stream().anyMatch(report -> report.evidenceVersion() >= evidenceVersion)) {
                return;
            }
            Optional<SecurityCase> securityCase = securityCaseRepository.findById(caseId);
            if (securityCase.isEmpty()) {
                return;
            }
            int reportVersion = caseReports.size() + 1;
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            List<PaymentInfo> payments = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                    .map(CasePayment::getPaymentId)
                    .map(paymentInfoReader::find)
                    .flatMap(Optional::stream)
                    .toList();
            // 결제 연결과 버전 증가는 한 트랜잭션으로 커밋되므로, 결제를 읽은 뒤 버전이 요청과 같으면 둘이 같은 시점이다
            // 그사이 병합돼 버전이 앞섰으면 낡은 요청이다 — 건너뛰고 새 버전 요청이 보고서를 만든다
            boolean stale = securityCase.get().getEvidenceVersion() != evidenceVersion
                    || securityCaseRepository
                                    .findById(caseId)
                                    .map(SecurityCase::getEvidenceVersion)
                                    .orElse(-1)
                            != evidenceVersion;
            if (stale) {
                return;
            }
            caseReports.add(new ReportSnapshot(
                    reportIds.incrementAndGet(),
                    caseId,
                    reportVersion,
                    evidenceVersion,
                    "SUCCEEDED",
                    body(securityCase.get(), payments, reportVersion, evidenceVersion, now),
                    now,
                    now));
        }
    }

    @Override
    public List<ReportSnapshot> findByCase(Long caseId) {
        return List.copyOf(reports.getOrDefault(caseId, List.of()));
    }

    private static Map<String, Object> body(
            SecurityCase securityCase,
            List<PaymentInfo> payments,
            int reportVersion,
            int evidenceVersion,
            Instant now) {
        long totalAmount = payments.stream().mapToLong(PaymentInfo::amount).sum();
        String dataVersion = "evidence v" + evidenceVersion;
        List<Map<String, Object>> analyses = new ArrayList<>();
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (PaymentInfo payment : payments) {
            analyses.add(Map.of(
                    "paymentId", payment.paymentId(),
                    "amount", payment.amount(),
                    "requestedAt", payment.requestedAt().toString(),
                    "riskScore", payment.riskScore(),
                    "ruleHits", payment.ruleHits(),
                    "riskEvidence",
                            payment.ruleHits().stream()
                                    .map(rule -> rule + " 규칙 적중")
                                    .toList(),
                    "normalEvidence", List.of("기존 배송지와 일치"),
                    "unknowns", List.of("고객 본인의 결제 의사"),
                    "recommendation", "NEEDS_CHECK"));
            timeline.add(Map.of(
                    "at",
                    payment.requestedAt().toString(),
                    "event",
                    payment.paymentId() + " 결제 요청 · FDS REVIEW (" + payment.riskScore() + "점)"));
        }
        return Map.of(
                "overview",
                Map.of(
                        "caseId", securityCase.getId(),
                        "merchantId", securityCase.getMerchantId(),
                        "customerRef", securityCase.getCustomerRef(),
                        "paymentCount", payments.size(),
                        "totalAmount", totalAmount,
                        "caseCreatedAt", securityCase.getCreatedAt().toString(),
                        "investigatedAt", now.toString(),
                        "reportVersion", reportVersion),
                "summary",
                List.of(
                        "조사 대상 결제 " + payments.size() + "건에서 FDS 규칙 적중이 확인되었습니다.",
                        "직원은 결제별 위험 근거와 정상 정황을 비교한 뒤 판정해 주세요."),
                "paymentAnalyses",
                analyses,
                "timeline",
                timeline,
                "overallAnalysis",
                List.of("결제 간 공통 기기·계정 관계는 확인되지 않았습니다."),
                "additionalChecks",
                List.of("고객 본인 확인 필요 여부"),
                "recommendation",
                Map.of(
                        "summary",
                        "추가 확인 후 결제별로 판정할 것을 권고합니다.",
                        "limitations",
                        List.of("목업 보고서입니다. FDS 점수는 부정거래 확률이 아닙니다.")),
                "evidence",
                List.of(
                        evidence(
                                "get_case_payments", "P3·P5", securityCase.getId(), reportVersion, 1, now, dataVersion),
                        evidence("get_fds_evidence", "P2", securityCase.getId(), reportVersion, 2, now, dataVersion)));
    }

    private static Map<String, Object> evidence(
            String toolName, String source, Long caseId, int reportVersion, int seq, Instant now, String dataVersion) {
        return Map.of(
                "toolName", toolName,
                "source", source,
                "evidenceId", "E-" + caseId + "-v" + reportVersion + "-0" + seq,
                "retrievedAt", now.toString(),
                "dataVersion", dataVersion);
    }
}
